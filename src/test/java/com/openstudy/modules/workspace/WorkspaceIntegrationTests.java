package com.openstudy.modules.workspace;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.support.database.MySqlIntegrationTest;
import com.openstudy.modules.workspace.service.WorkspaceEventService;
import com.openstudy.modules.auth.service.SessionBoundaryPolicy;
import static org.assertj.core.api.Assertions.*;

@MySqlIntegrationTest
@Import(WorkspaceIntegrationTests.TimeConfiguration.class)
@TestPropertySource(properties={"openstudy.workspace.scheduling.enabled=false","openstudy.workspace.heartbeat-millis=1000"})
class WorkspaceIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired WorkspaceEventService events;
    @Autowired PlatformTransactionManager transactions;
    @Autowired SessionRepository<? extends org.springframework.session.Session> sessions;
    @Autowired MutableClock clock;
    @LocalServerPort int port;
    final List<Long> people=new ArrayList<>(),branches=new ArrayList<>();
    final List<Client> clients=new ArrayList<>();
    final List<Stream> streams=new ArrayList<>();
    long owner,manager,staff,other,pending,limited,unassigned,first,second,stopped;
    String passwordHash;
    static final String PASSWORD="workspace-test-password";

    static class MutableClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(Instant.now());
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now.get();}
        void advance(long seconds){now.updateAndGet(time->time.plusSeconds(seconds));}
    }
    @TestConfiguration(proxyBeanMethods=false) static class TimeConfiguration {
        @Bean @Primary MutableClock workspaceClock(){return new MutableClock();}
    }
    @BeforeEach void fixture() {
        clock.now.set(Instant.now());
        passwordHash=passwords.encode(PASSWORD);
        owner=person("OWNER","ACTIVE");manager=person("EMPLOYEE","ACTIVE");staff=person("EMPLOYEE","ACTIVE");
        other=person("EMPLOYEE","ACTIVE");pending=person("EMPLOYEE","PENDING");limited=person("EMPLOYEE","SUSPENDED");
        unassigned=person("EMPLOYEE","ACTIVE");
        first=branch("ACTIVE");second=branch("ACTIVE");stopped=branch("SUSPENDED");
        membership(manager,first,"MANAGER");membership(staff,first,"STAFF");membership(staff,stopped,"STAFF");
        membership(other,second,"STAFF");membership(pending,first,"STAFF");membership(limited,first,"STAFF");
        jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id FROM branch_memberships m "
                + "JOIN permission_definitions p ON p.code='people:manage' WHERE m.employee_id=?",manager);
    }
    @AfterEach void cleanup() {
        for(long id:people) jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?",Long.toString(id));
        events.tick();
        streams.forEach(Stream::close);streams.clear();
        clients.forEach(Client::close);clients.clear();
        for(long id:people) {
            jdbc.update("DELETE FROM audit_events WHERE actor_employee_id=?",id);
            jdbc.update("DELETE FROM workspace_event_outbox WHERE target_employee_id=? OR (entity_type IN ('employee','employees') AND entity_id=?)",id,id);
            jdbc.update("DELETE FROM branch_memberships WHERE employee_id=?",id);
            jdbc.update("UPDATE employees SET created_by=NULL,updated_by=NULL WHERE id=?",id);
        }
        for(long id:people) jdbc.update("DELETE FROM employees WHERE id=?",id);
        for(long id:branches) {
            jdbc.update("DELETE FROM workspace_event_outbox WHERE branch_id=? OR (entity_type='branches' AND entity_id=?)",id,id);
            jdbc.update("DELETE FROM branches WHERE id=?",id);
        }
        people.clear();branches.clear();
    }
    long person(String role,String status) {
        String email=UUID.randomUUID()+"@workspace.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES (?,?,'선택 이름','별명',?,?)",email,passwordHash,role,status);
        long id=jdbc.queryForObject("SELECT id FROM employees WHERE email=?",Long.class,email);people.add(id);return id;
    }
    long branch(String status) {
        String code=UUID.randomUUID().toString().substring(0,20);
        jdbc.update("INSERT INTO branches(code,name,address,status) VALUES (?,'검증 지점','서울',?)",code,status);
        long id=jdbc.queryForObject("SELECT id FROM branches WHERE code=?",Long.class,code);branches.add(id);return id;
    }
    void membership(long employee,long branch,String role) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES (?,?,?,'ACTIVE')",employee,branch,role);
    }
    Client client(long actor) throws Exception {var client=new Client(actor);clients.add(client);return client;}
    final class Client implements AutoCloseable {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient http=HttpClient.newBuilder().cookieHandler(cookies).build();
        String csrf;
        Client(long actor) throws Exception {
            csrf=json.readTree(call("GET","/api/auth/csrf",null).body()).get("token").asString();
            String email=jdbc.queryForObject("SELECT email FROM employees WHERE id=?",String.class,actor);
            assertThat(call("POST","/api/auth/login",Map.of("email",email,"password",PASSWORD)).statusCode()).isEqualTo(200);
            csrf=json.readTree(call("GET","/api/auth/csrf",null).body()).get("token").asString();
        }
        HttpRequest.Builder request(String path){return HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));}
        HttpResponse<String> call(String method,String path,Object body)throws Exception {
            var request=request(path);
            if(csrf!=null)request.header("X-CSRF-Token",csrf);
            if(body!=null)request.header("Content-Type","application/json");
            request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
        }
        Stream stream(String scope)throws Exception {
            var response=http.send(request("/api/workspace/events?scope="+scope).header("Accept","text/event-stream").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("text/event-stream");
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
            assertThat(response.headers().firstValue("X-Accel-Buffering").orElseThrow()).isEqualTo("no");
            var stream=new Stream(response.body());streams.add(stream);return stream;
        }
        String sessionId(){String value=cookies.getCookieStore().getCookies().stream().filter(c->"SESSION".equals(c.getName())).findFirst().orElseThrow().getValue().replace("\"","");return new String(Base64.getDecoder().decode(value),java.nio.charset.StandardCharsets.UTF_8);}
        public void close(){http.close();}
    }
    final class Stream implements AutoCloseable {
        final InputStream body;
        final BlockingQueue<String> frames=new LinkedBlockingQueue<>();
        final List<String> received=Collections.synchronizedList(new ArrayList<>());
        final Thread reader;
        Stream(InputStream body) {
            this.body=body;
            reader=Thread.ofVirtual().start(()->{
                try(var lines=new BufferedReader(new InputStreamReader(body,java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;StringBuilder frame=new StringBuilder();
                    while((line=lines.readLine())!=null){if(line.isEmpty()){if(!frame.isEmpty()){String value=frame.toString();received.add(value);frames.add(value);frame.setLength(0);}}else frame.append(line).append('\n');}
                }catch(IOException ignored){}finally{frames.add("EOF");}
            });
        }
        String next()throws Exception {String result=frames.poll(5,TimeUnit.SECONDS);assertThat(result).as("SSE frame within five seconds").isNotNull();return result;}
        void resync()throws Exception {assertThat(next()).contains("event:resync","\"entityId\":null","\"branchId\":null");}
        void none()throws Exception {assertThat(frames.poll(200,TimeUnit.MILLISECONDS)).isNull();}
        public void close(){try{body.close();reader.join(500);}catch(IOException|InterruptedException ignored){}}
    }
    void event(long branch,String type,long entity) {
        jdbc.update("INSERT INTO workspace_event_outbox(branch_id,entity_type,entity_id,event_type) VALUES (?,?,?,'INVALIDATE')",branch,type,entity);
    }
    @Test void lookupsAreCanonicalScopedAndMinimumAndPendingOrAnonymousAreDenied()throws Exception {
        var managerClient=client(manager);var staffClient=client(staff);
        var response=managerClient.call("GET","/api/lookups?scope="+first,null);
        assertThat(response.statusCode()).isEqualTo(200);
        var data=json.readTree(response.body());
        assertThat(data.size()).isEqualTo(8);assertThat(data.get("branches").size()).isEqualTo(1);
        assertThat(data.get("employees")).hasSize(3);
        for(var employee:data.get("employees")){assertThat(employee.size()).isEqualTo(2);assertThat(employee.has("id")).isTrue();assertThat(employee.has("name")).isTrue();}
        assertThat(response.body()).doesNotContain("@workspace.test",passwordHash,"password_hash","avatar","nickname");
        assertThat(data.get("permissionDefinitions").size()).isEqualTo(9);assertThat(data.get("permissionPresets").size()).isEqualTo(2);
        var staffData=json.readTree(staffClient.call("GET","/api/lookups?scope=all",null).body());
        assertThat(staffData.get("permissionPresets").isEmpty()).isTrue();assertThat(staffData.get("branches").size()).isEqualTo(2);
        assertThat(staffData.get("products").isEmpty()).isTrue();assertThat(staffData.get("productCategories").isEmpty()).isTrue();
        assertThat(staffData.get("branches").toString()).contains("운영 중지").doesNotContain('"'+Long.toString(second)+'"');
        assertThat(staffClient.call("GET","/api/lookups?scope="+second,null).statusCode()).isEqualTo(403);
        assertThat(staffClient.call("GET","/api/lookups?scope=bad",null).statusCode()).isEqualTo(422);
        var empty=json.readTree(client(unassigned).call("GET","/api/lookups",null).body());
        assertThat(empty.get("branches").isEmpty()).isTrue();assertThat(empty.get("employees").isEmpty()).isTrue();
        assertThat(client(pending).call("GET","/api/lookups",null).statusCode()).isEqualTo(403);
        try(var anonymous=HttpClient.newHttpClient()) {
            assertThat(anonymous.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/lookups")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
    }
    @Test void rollbackAndUncommittedEventsDoNotEscapeAndReconnectAlwaysResyncs()throws Exception {
        var browser=client(staff);var stream=browser.stream(Long.toString(first));stream.resync();
        var tx=new TransactionTemplate(transactions);
        tx.execute(status->{event(first,"branches",first);events.tick();status.setRollbackOnly();return null;});
        stream.none();
        tx.execute(status->{event(first,"branches",first);events.tick();return null;});
        stream.none();events.tick();
        assertThat(stream.next()).contains("event:invalidate","\"branchId\":\""+first+"\"","\"resource\":\"branches\"");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=? AND published_at IS NOT NULL",Long.class,first)).isEqualTo(1);
        var reconnected=browser.stream(Long.toString(first));reconnected.resync();
    }
    @Test void branchEventsAndTargetedSignalsCannotLeakToOtherEmployeesOrScopes()throws Exception {
        var ours=client(staff).stream(Long.toString(first));ours.resync();
        var theirs=client(other).stream(Long.toString(second));theirs.resync();
        event(first,"branches",first);event(second,"branches",second);events.tick();
        assertThat(ours.next()).contains("\"branchId\":\""+first+"\"").doesNotContain("\"branchId\":\""+second+"\"");
        assertThat(theirs.next()).contains("\"branchId\":\""+second+"\"");
        jdbc.update("INSERT INTO workspace_event_outbox(target_employee_id,entity_type,entity_id,event_type) VALUES (?,'employee',?,'AUTHORIZATION_CHANGED')",staff,staff);
        events.tick();
        assertThat(ours.next()).contains("event:authorization.changed","\"entityId\":null","\"branchId\":null");
        assertThat(ours.next()).isEqualTo("EOF");theirs.none();
        assertThat(ours.received.toString()).doesNotContain("email","nickname",passwordHash);
    }
    @Test void aLowerOutboxIdThatCommitsLateIsNotLostAfterALaterIdWasPublished()throws Exception {
        var stream=client(staff).stream(Long.toString(first));stream.resync();
        var inserted=new CountDownLatch(1);var commit=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var earlier=pool.submit(()->new TransactionTemplate(transactions).execute(status->{
                event(first,"branches",first);inserted.countDown();
                try {if(!commit.await(10,TimeUnit.SECONDS))throw new IllegalStateException("commit timeout");}
                catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
                return null;
            }));
            try {
                assertThat(inserted.await(5,TimeUnit.SECONDS)).isTrue();
                event(first,"branches",first);events.tick();
                assertThat(stream.next()).contains("event:invalidate");stream.none();
            } finally {commit.countDown();}
            earlier.get(10,TimeUnit.SECONDS);
        }
        events.tick();assertThat(stream.next()).contains("event:invalidate");stream.none();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=? AND published_at IS NOT NULL",Long.class,first)).isEqualTo(2);
    }
    @Test void committedMembershipRevocationClosesAnOpenStreamBeforeAnyBranchEvent()throws Exception {
        var browser=client(staff);var stream=browser.stream(Long.toString(first));stream.resync();
        jdbc.update("UPDATE branch_memberships SET status='REVOKED',version=version+1 WHERE employee_id=? AND branch_id=?",staff,first);
        event(first,"branches",first);events.tick();
        assertThat(stream.next()).contains("event:authorization.changed","\"branchId\":null");assertThat(stream.next()).isEqualTo("EOF");
        assertThat(browser.call("GET","/api/lookups?scope="+first,null).statusCode()).isEqualTo(403);
        assertThat(stream.received.toString()).doesNotContain("event:invalidate");
    }
    @Test void branchEmployeeRevocationInvalidatesStaffWithoutRevealingTheRemovedEmployeeId()throws Exception {
        var administrator=client(owner);var viewer=client(staff);
        var before=json.readTree(administrator.call("GET","/api/employees/"+manager+"?scope="+first,null).body());
        var choices=json.readTree(viewer.call("GET","/api/lookups?scope="+first,null).body());
        assertThat(choices.get("employees")).anyMatch(employee->Long.toString(manager).equals(employee.get("id").asString()));
        assertThat(viewer.call("GET","/api/employees/"+manager+"?scope="+first,null).statusCode()).isEqualTo(403);
        var stream=viewer.stream(Long.toString(first));stream.resync();
        var response=administrator.call("PUT","/api/employees/"+manager+"/memberships/"+first+"?scope="+first,
                Map.of("employeeVersion",before.get("version").asLong(),"version",before.get("memberships").get(0).get("version").asLong(),
                        "role","admin","status","회수","permissions",List.of()));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        events.tick();
        var frame=stream.next();
        assertThat(frame).contains("event:invalidate","\"resource\":\"employees\"","\"branchId\":\""+first+"\"","\"entityId\":null")
                .doesNotContain("\"entityId\":\""+manager+"\"");
        stream.none();
        var after=json.readTree(viewer.call("GET","/api/lookups?scope="+first,null).body());
        assertThat(after.get("employees")).noneMatch(employee->Long.toString(manager).equals(employee.get("id").asString()));
    }
    @Test void logoutAndAccountRestrictionTerminateStreamsWithoutJsonAppending()throws Exception {
        var browser=client(staff);var stream=browser.stream("all");stream.resync();
        assertThat(browser.call("POST","/api/auth/logout",Map.of()).statusCode()).isEqualTo(200);events.tick();
        assertThat(stream.next()).isEqualTo("EOF");
        assertThat(stream.received.toString()).doesNotContain("SESSION_EXPIRED","FORBIDDEN","event:invalidate");
        var restricted=client(manager);var another=restricted.stream(Long.toString(first));another.resync();
        jdbc.update("UPDATE employees SET status='SUSPENDED' WHERE id=?",manager);events.tick();
        assertThat(another.next()).isEqualTo("EOF");
        assertThat(restricted.call("GET","/api/me",null).statusCode()).isEqualTo(401);
    }
    @Test void heartbeatNeverExtendsIdleSessionAndMaximumAuthenticationAgeClosesStream()throws Exception {
        var browser=client(staff);String id=browser.sessionId();
        Long before=sessions.findById(id).getAttribute(SessionBoundaryPolicy.LAST_ACTIVITY_AT);
        var stream=browser.stream("all");stream.resync();
        clock.advance(2);events.tick();assertThat(stream.next()).contains(":heartbeat");
        assertThat(sessions.findById(id).<Long>getAttribute(SessionBoundaryPolicy.LAST_ACTIVITY_AT)).isEqualTo(before);
        clock.advance(SessionBoundaryPolicy.IDLE_SECONDS-2);events.tick();assertThat(stream.next()).isEqualTo("EOF");
        assertThat(browser.call("GET","/api/me",null).statusCode()).isEqualTo(401);
        clock.now.set(Instant.now());var active=client(manager);var max=active.stream("all");max.resync();
        clock.advance(SessionBoundaryPolicy.MAX_SECONDS);
        setActivity(sessions,active.sessionId(),clock.instant().getEpochSecond()-1);
        events.tick();assertThat(max.next()).isEqualTo("EOF");
    }
    private <S extends org.springframework.session.Session> void setActivity(SessionRepository<S> repository,String id,long at) {
        S session=repository.findById(id);session.setAttribute(SessionBoundaryPolicy.LAST_ACTIVITY_AT,at);repository.save(session);
    }
    @Test void globalEmployeeAndPresetInvalidationsRedactIdsAndRespectVisibility()throws Exception {
        var employee=client(staff).stream(Long.toString(first));employee.resync();
        var administrator=client(manager).stream(Long.toString(first));administrator.resync();
        jdbc.update("INSERT INTO workspace_event_outbox(entity_type,entity_id,event_type) VALUES ('employees',?,'INVALIDATE')",other);
        events.tick();employee.none();administrator.none();
        jdbc.update("INSERT INTO workspace_event_outbox(entity_type,entity_id,event_type) VALUES ('employees',?,'INVALIDATE')",manager);
        events.tick();
        assertThat(employee.next()).contains("\"resource\":\"lookups\"","\"entityId\":null","\"branchId\":null");
        assertThat(administrator.next()).contains("\"entityId\":null");
        long preset=jdbc.queryForObject("SELECT MIN(id) FROM permission_presets",Long.class);
        jdbc.update("INSERT INTO workspace_event_outbox(entity_type,entity_id,event_type) VALUES ('permission-presets',?,'INVALIDATE')",preset);
        events.tick();employee.none();assertThat(administrator.next()).contains("\"resource\":\"permissions\"","\"entityId\":null");
        jdbc.update("DELETE FROM workspace_event_outbox WHERE entity_type='permission-presets' AND entity_id=?",preset);
    }
}
