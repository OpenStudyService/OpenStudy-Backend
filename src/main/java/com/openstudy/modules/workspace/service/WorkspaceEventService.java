package com.openstudy.modules.workspace.service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.workspace.dto.WorkspaceEvent;
import com.openstudy.modules.workspace.repository.WorkspaceOutboxRepository;
import com.openstudy.modules.workspace.repository.WorkspaceOutboxRepository.Event;

/** Single-process, best-effort invalidation. Every reconnect obtains current data through resync. */
@Service
public class WorkspaceEventService {
    private static final Logger log=LoggerFactory.getLogger(WorkspaceEventService.class);
    private final WorkspaceAccessService access;
    private final AuthorizationService authorization;
    private final EmployeeAccountService employees;
    private final WorkspaceOutboxRepository outbox;
    private final Clock clock;
    private final long heartbeatMillis;
    private final int maxConnections;
    private final Map<String,Connection> connections=new ConcurrentHashMap<>();
    private final AtomicBoolean polling=new AtomicBoolean();

    private static final class Connection {
        final String key=UUID.randomUUID().toString(),sessionId,scope,fingerprint;
        final long actor;
        final SseEmitter emitter=new SseEmitter(0L);
        Instant heartbeat;
        long sequence;
        Connection(long actor,String sessionId,String scope,String fingerprint,Instant now) {
            this.actor=actor;this.sessionId=sessionId;this.scope=scope;this.fingerprint=fingerprint;this.heartbeat=now;
        }
    }

    public WorkspaceEventService(WorkspaceAccessService access,AuthorizationService authorization,
            EmployeeAccountService employees,WorkspaceOutboxRepository outbox,Clock clock,
            @Value("${openstudy.workspace.heartbeat-millis:15000}") long heartbeatMillis,
            @Value("${openstudy.workspace.max-connections:512}") int maxConnections) {
        if(heartbeatMillis<1 || maxConnections<1) throw new IllegalArgumentException("Invalid workspace stream configuration");
        this.access=access;this.authorization=authorization;this.employees=employees;this.outbox=outbox;
        this.clock=clock;this.heartbeatMillis=heartbeatMillis;this.maxConnections=maxConnections;
    }

    public SseEmitter open(long actor,String sessionId,String scope) {
        var current=access.current(actor,sessionId,scope);
        if(!current.permitted()) throw AuthorizationService.forbidden();
        synchronized(connections) {
            if(connections.size()>=maxConnections) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
            var connection=new Connection(actor,sessionId,scope,current.fingerprint(),clock.instant());
            connection.emitter.onCompletion(()->connections.remove(connection.key));
            connection.emitter.onTimeout(()->close(connection));
            connection.emitter.onError(failure->connections.remove(connection.key));
            send(connection,new WorkspaceEvent("resync","lookups",null,null,clock.instant().toString()));
            // Publish only after the initial resync was queued, so a concurrent tick cannot overtake it.
            connections.put(connection.key,connection);
            return connection.emitter;
        }
    }

    /** Also callable by deterministic HTTP/DB integration tests with scheduling disabled. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void tick() {
        if(!polling.compareAndSet(false,true)) return;
        try {
            for(var connection:connections.values()) {
                if(validate(connection)==null) continue;
                Instant now=clock.instant();
                if(java.time.Duration.between(connection.heartbeat,now).toMillis()>=heartbeatMillis) {
                    // A comment keeps the transport open without carrying model data or extending the session.
                    try { connection.emitter.send(SseEmitter.event().comment("heartbeat")); connection.heartbeat=now; }
                    catch(IOException|IllegalStateException failure) { close(connection); }
                }
            }
            for(Event event:outbox.pending()) {
                for(var connection:connections.values()) dispatch(connection,event);
                outbox.published(event.id());
            }
        } catch(RuntimeException failure) {
            // Fail closed when current sessions/authorization cannot be read; never log SQL or event contents.
            log.warn("Workspace dispatch failed type={}",failure.getClass().getSimpleName());
            connections.values().forEach(this::close);
        } finally { polling.set(false); }
    }

    private WorkspaceAccessService.Access validate(Connection connection) {
        try {
            var current=access.current(connection.actor,connection.sessionId,connection.scope);
            if(!connection.fingerprint.equals(current.fingerprint())) {
                send(connection,new WorkspaceEvent("authorization.changed","permissions",null,null,clock.instant().toString()));
                close(connection);return null;
            }
            if(!current.permitted()) { close(connection);return null; }
            return current;
        } catch(RuntimeException deniedOrUnavailable) { close(connection);return null; }
    }

    private void dispatch(Connection connection,Event event) {
        // Targeted signals never reach a different employee, even if that employee is an OWNER.
        if(event.targetEmployeeId()!=null && event.targetEmployeeId()!=connection.actor) return;
        var current=validate(connection);
        if(current==null) return;
        if("AUTHORIZATION_CHANGED".equals(event.eventType())) {
            if(event.targetEmployeeId()==null) return;
            send(connection,new WorkspaceEvent("authorization.changed","permissions",null,null,event.occurredAt().toString()));
            close(connection);return;
        }
        String resource=resource(event.entityType());
        if(resource==null) return;
        if(event.targetEmployeeId()!=null) {
            // Personal profile updates need /me as well as screen data to be reloaded in other sessions.
            send(connection,new WorkspaceEvent("resync","lookups",null,null,event.occurredAt().toString()));return;
        }
        if(event.branchId()!=null) {
            if(!current.branches().contains(event.branchId())) return;
            if("invitations".equals(resource) && !authorization.permits(current.state(),event.branchId(),BranchAction.MANAGE_PEOPLE)) return;
            // A removed peer is no longer in minimal lookups, even while this branch remains readable.
            String entityId="employees".equals(resource) || event.entityId()==null ? null : event.entityId().toString();
            send(connection,new WorkspaceEvent("invalidate",resource,
                    entityId,event.branchId().toString(),event.occurredAt().toString()));
        } else if("employees".equals(resource)) {
            if(event.entityId()!=null && employees.relatedToBranches(event.entityId(),current.branches()))
                send(connection,new WorkspaceEvent("invalidate","lookups",null,null,event.occurredAt().toString()));
        } else if("permissions".equals(resource) && current.branches().stream()
                .anyMatch(branch->authorization.permits(current.state(),branch,BranchAction.MANAGE_PEOPLE))) {
            send(connection,new WorkspaceEvent("invalidate",resource,null,null,event.occurredAt().toString()));
        }
    }

    private static String resource(String entity) {
        return switch(entity) {
            case "branch","branches"->"branches";
            case "employee","employees"->"employees";
            case "permission-preset","permission-presets","permissions"->"permissions";
            case "invitation","invitations"->"invitations";
            case "product","products","product-category","product-categories"->"products";
            case "lookups"->"lookups";
            default->null;
        };
    }
    private void send(Connection connection,WorkspaceEvent event) {
        try {
            // IDs are connection-local hints, not an outbox/replay cursor or a cross-scope activity counter.
            connection.emitter.send(SseEmitter.event().id(Long.toString(++connection.sequence)).name(event.type()).data(event));
        } catch(IOException|IllegalStateException failure) { close(connection); }
    }
    private void close(Connection connection) {
        if(connections.remove(connection.key)!=null) connection.emitter.complete();
    }
}
