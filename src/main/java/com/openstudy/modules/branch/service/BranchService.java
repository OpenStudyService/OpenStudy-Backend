package com.openstudy.modules.branch.service;

import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.outbox.WorkspaceOutbox;
import com.openstudy.common.web.*;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.branch.dto.*;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.entity.BranchStatus;
import com.openstudy.modules.branch.repository.BranchRepository;

@Service
public class BranchService {
    private static final String CREATE="branches:create";
    private final BranchRepository repository;
    private final EntityManager entityManager;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final ObjectMapper json;
    private final AuditTrail audit;
    private final WorkspaceOutbox outbox;
    private final IdempotencyStore requests;
    private final Clock clock;
    public BranchService(BranchRepository repository,EntityManager entityManager,AuthorizationService policy,AuthorizedCommands commands,
            ObjectMapper json,AuditTrail audit,WorkspaceOutbox outbox,IdempotencyStore requests,Clock clock) {
        this.repository=repository;this.entityManager=entityManager;this.policy=policy;this.commands=commands;this.json=json;
        this.audit=audit;this.outbox=outbox;this.requests=requests;this.clock=clock;
    }
    @Transactional(readOnly=true)
    public PageResponse<Branch> list(long actor,String scope,ListQuery query,String status) {
        if(status!=null) status(status);
        Set<Long> allowed=policy.scope(actor,scope,BranchAction.READ);
        var rows=repository.findAllWithWeeklyHoursByOrderByIdAsc().stream().filter(b->allowed.contains(b.getId())).map(BranchViews::toDto)
                .filter(b->status==null||status.equals(b.status()))
                .filter(b->(b.name()+" "+b.address()+" "+b.code()).toLowerCase(Locale.ROOT).contains(query.q().toLowerCase(Locale.ROOT))).toList();
        Comparator<Branch> field="name".equals(query.sort())?Comparator.comparing(Branch::name):Comparator.comparing(Branch::createdAt);
        return PageResponse.fromAuthorized(rows,query,field,Comparator.comparingLong(b->Long.parseLong(b.id())));
    }
    @Transactional(readOnly=true)
    public List<Branch> lookup(long actor, String scope) {
        Set<Long> allowed=policy.scope(actor,scope,BranchAction.READ);
        return repository.findAllWithWeeklyHoursByOrderByIdAsc().stream().filter(b->allowed.contains(b.getId())).map(BranchViews::toDto).toList();
    }
    @Transactional(readOnly=true)
    public Branch get(long actor,long id) {
        // Require access before reading the branch or its schedule; no existence oracle outside scope.
        policy.require(policy.current(actor),Set.of(id),BranchAction.READ);
        return BranchViews.toDto(entity(id));
    }
    public Branch create(long actor,String key,BranchRequests.Create body,String requestId) {
        if(key==null||key.length()<16||key.length()>128) throw ApiException.validation("Idempotency-Key","16~128자 키가 필요합니다.");
        var normalized=new BranchRequests.Create(text(body.name(),"name"),text(body.address(),"address"),body.phone(),hours(body.weeklyHours()),body.status());
        String databaseStatus=status(body.status());
        return commands.ownerCommand(actor,()->{
            byte[] fingerprint=digest(json.writeValueAsString(normalized));
            var prior=requests.lock(actor,CREATE,"all",key);
            if(prior.isPresent()) {
                if(!MessageDigest.isEqual(fingerprint,prior.get().getRequestHash())) throw ApiException.conflict("IDEMPOTENCY_CONFLICT","같은 키로 다른 입력을 저장할 수 없습니다.");
                return json.readValue(prior.get().getResponseJson(),Branch.class);
            }
            String code="BR-"+UUID.randomUUID().toString().replace("-", "").substring(0,29);
            // created_by/updated_by stay NULL as before this migration; the actor is recorded in audit_events.
            var branch=new com.openstudy.modules.branch.entity.Branch(code,normalized.name(),normalized.address(),normalized.phone(),
                    BranchStatus.valueOf(databaseStatus),null);
            for(var h:normalized.weeklyHours()) branch.setWeeklyHour(h.weekday(),h.isClosed(),h.opensMinute(),h.closesMinute());
            repository.saveAndFlush(branch);
            entityManager.refresh(branch);
            var result=BranchViews.toDto(branch);String snapshot=json.writeValueAsString(result);
            recordChange(actor,branch.getId(),"CREATED",null,snapshot,requestId);
            requests.complete(actor,CREATE,"all",key,fingerprint,201,snapshot,clock.instant().plus(Duration.ofHours(24)));
            return result;
        });
    }
    public Branch update(long actor,long id,BranchRequests.Update body,String requestId) {
        ApiVersion.toDatabase(body.version());
        if(body.name()==null&&body.address()==null&&body.phone()==null&&body.weeklyHours()==null) throw ApiException.validation("body","변경할 항목이 필요합니다.");
        var schedule=body.weeklyHours()==null?null:hours(body.weeklyHours());
        return commands.inBranches(actor,Set.of(id),BranchAction.MANAGE_BRANCH,()->{
            var branch=entity(id);var before=BranchViews.toDto(branch); checkVersion(before,body.version());
            long version=branch.getVersion();
            branch.update(body.name()==null?before.name():text(body.name(),"name"),body.address()==null?before.address():text(body.address(),"address"),
                    body.phone()==null?before.phone():body.phone());
            for(var h:schedule==null?before.weeklyHours():schedule) branch.setWeeklyHour(h.weekday(),h.isClosed(),h.opensMinute(),h.closesMinute());
            var result=saveChange(branch,version);recordChange(actor,id,"UPDATED",json.writeValueAsString(before),json.writeValueAsString(result),requestId);
            return result;
        });
    }
    public Branch changeStatus(long actor,long id,BranchRequests.Status body,String requestId) {
        ApiVersion.toDatabase(body.version());String state=status(body.status());
        return commands.inBranches(actor,Set.of(id),BranchAction.MANAGE_BRANCH,()->{
            var branch=entity(id);var before=BranchViews.toDto(branch);checkVersion(before,body.version());
            long version=branch.getVersion();branch.changeStatus(BranchStatus.valueOf(state));
            var result=saveChange(branch,version);recordChange(actor,id,"STATUS_CHANGED",json.writeValueAsString(before),json.writeValueAsString(result),requestId);
            return result;
        });
    }
    private com.openstudy.modules.branch.entity.Branch entity(long id) {
        return repository.findWithWeeklyHoursById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    /**
     * Every accepted command advances the version by exactly one, even when no column differs
     * (the former SQL always ran version=version+1). Hibernate only bumps a dirty branch row, and weekly-hour
     * rows are separate entities, so an unchanged row gets one explicit increment. A bulk UPDATE is used instead of
     * a FORCE_INCREMENT lock, which Hibernate skips when the entity already holds that lock in the same context.
     * The refresh re-reads the DB-maintained updated_at.
     */
    private Branch saveChange(com.openstudy.modules.branch.entity.Branch branch,long versionBefore) {
        repository.flush();
        if(branch.getVersion()==versionBefore) repository.incrementVersion(branch.getId());
        entityManager.refresh(branch);
        return BranchViews.toDto(branch);
    }
    private void recordChange(long actor,long id,String action,String before,String after,String requestId) {
        audit.record(AuditEvent.change(actor,id,"branches",id,action,before,after,requestId));
        outbox.invalidate(id,"branches",id);
    }
    private static void checkVersion(Branch branch,long supplied) {
        if(branch.version()!=supplied) throw ApiException.versionConflict(branch.version(),"/api/branches/"+branch.id());
    }
    private static String text(String value,String field) {
        if(value==null||value.isBlank()) throw ApiException.validation(field,"값을 입력해 주세요."); return value.strip();
    }
    private static String status(String status) {
        return switch(status==null?"":status) {
            case "운영 중"->"ACTIVE";case "운영 중지"->"SUSPENDED";
            default->throw ApiException.validation("status","올바른 지점 상태를 선택해 주세요.");
        };
    }
    private static List<WeeklyHours> hours(List<WeeklyHours> hours) {
        if(hours==null||hours.size()!=7) throw ApiException.validation("weeklyHours","7개 요일을 모두 지정해 주세요.");
        Set<Integer> days=new HashSet<>();
        for(var h:hours) {
            if(h==null||h.weekday()==null||h.weekday()<1||h.weekday()>7||!days.add(h.weekday())||h.isClosed()==null)
                throw ApiException.validation("weeklyHours","요일은 1~7까지 중복 없이 지정해 주세요.");
            if(h.isClosed() ? h.opensMinute()!=null||h.closesMinute()!=null
                    : h.opensMinute()==null||h.closesMinute()==null||h.opensMinute()<0||h.opensMinute()>1439||h.closesMinute()>2880||h.closesMinute()<=h.opensMinute())
                throw ApiException.validation("weeklyHours","휴무는 시각 없이, 영업은 시작보다 늦은 종료 분을 지정해 주세요.");
        }
        return hours.stream().sorted(Comparator.comparing(WeeklyHours::weekday)).toList();
    }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch(java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
