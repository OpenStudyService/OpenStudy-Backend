package com.openstudy.modules.workspace;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
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
import tools.jackson.databind.ObjectMapper;
import com.openstudy.support.database.MySqlIntegrationTest;
import com.openstudy.modules.auth.service.SessionBoundaryPolicy;
import static org.assertj.core.api.Assertions.*;

@MySqlIntegrationTest
@Import(WorkspaceIntegrationTests.TimeConfiguration.class)
class WorkspaceIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired SessionRepository<? extends org.springframework.session.Session> sessions;
    @Autowired MutableClock clock;
    @LocalServerPort int port;
    final List<Long> people=new ArrayList<>(),branches=new ArrayList<>();
    final List<Client> clients=new ArrayList<>();
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
        clients.forEach(Client::close);clients.clear();
        for(long id:people) {
            jdbc.update("DELETE FROM audit_events WHERE actor_employee_id=?",id);
            jdbc.update("DELETE FROM branch_memberships WHERE employee_id=?",id);
            jdbc.update("UPDATE employees SET created_by=NULL,updated_by=NULL WHERE id=?",id);
        }
        for(long id:people) jdbc.update("DELETE FROM employees WHERE id=?",id);
        for(long id:branches) {
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
        String sessionId(){String value=cookies.getCookieStore().getCookies().stream().filter(c->"SESSION".equals(c.getName())).findFirst().orElseThrow().getValue().replace("\"","");return new String(Base64.getDecoder().decode(value),java.nio.charset.StandardCharsets.UTF_8);}
        public void close(){http.close();}
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
        assertThat(data.get("permissionDefinitions").size()).isEqualTo(10);assertThat(data.get("permissionPresets").size()).isEqualTo(2);
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
    /** ADR-COM-001 COM-D01: no change-notification stream is served, for the workspace or for an AI request. */
    @Test void noChangeNotificationStreamIsServed()throws Exception {
        var browser=client(staff);
        assertThat(browser.call("GET","/api/workspace/events?scope="+first,null).statusCode()).isEqualTo(404);
        // The AI paths check AI access first, so an OWNER shows that no such route exists at all.
        var administrator=client(owner);
        assertThat(administrator.call("GET","/api/ai/requests/any/events",null).statusCode()).isEqualTo(404);
        assertThat(administrator.call("GET","/api/requests/any/events",null).statusCode()).isEqualTo(404);
    }
    /** A committed change leaves no refresh row behind: nothing reads them any more, so none may pile up. */
    @Test void aCommittedChangeWritesNoRefreshRow()throws Exception {
        long rows=jdbc.queryForObject("SELECT COUNT(*) FROM workspace_event_outbox",Long.class);
        var administrator=client(owner);
        var before=json.readTree(administrator.call("GET","/api/employees/"+manager+"?scope="+first,null).body());
        var response=administrator.call("PUT","/api/employees/"+manager+"/memberships/"+first+"?scope="+first,
                Map.of("employeeVersion",before.get("version").asLong(),"version",before.get("memberships").get(0).get("version").asLong(),
                        "role","admin","status","회수","permissions",List.of()));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_event_outbox",Long.class)).isEqualTo(rows);
    }
    /** COM-D05: a membership revoked in another session refuses the next request with the current server state. */
    @Test void aRevokedMembershipRefusesTheNextRequest()throws Exception {
        var browser=client(staff);
        assertThat(browser.call("GET","/api/lookups?scope="+first,null).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE branch_memberships SET status='REVOKED',version=version+1 WHERE employee_id=? AND branch_id=?",staff,first);
        assertThat(browser.call("GET","/api/lookups?scope="+first,null).statusCode()).isEqualTo(403);
    }
    /** COM-D05: choices read after a revocation no longer offer the removed employee. */
    @Test void lookupsReadAfterARevocationDropTheRemovedEmployee()throws Exception {
        var administrator=client(owner);var viewer=client(staff);
        var before=json.readTree(administrator.call("GET","/api/employees/"+manager+"?scope="+first,null).body());
        var choices=json.readTree(viewer.call("GET","/api/lookups?scope="+first,null).body());
        assertThat(choices.get("employees")).anyMatch(employee->Long.toString(manager).equals(employee.get("id").asString()));
        assertThat(viewer.call("GET","/api/employees/"+manager+"?scope="+first,null).statusCode()).isEqualTo(403);
        var response=administrator.call("PUT","/api/employees/"+manager+"/memberships/"+first+"?scope="+first,
                Map.of("employeeVersion",before.get("version").asLong(),"version",before.get("memberships").get(0).get("version").asLong(),
                        "role","admin","status","회수","permissions",List.of()));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var after=json.readTree(viewer.call("GET","/api/lookups?scope="+first,null).body());
        assertThat(after.get("employees")).noneMatch(employee->Long.toString(manager).equals(employee.get("id").asString()));
    }
    /** COM-D05: logout and an account restricted elsewhere end the session at the next request. */
    @Test void logoutAndAccountRestrictionRefuseTheNextRequest()throws Exception {
        var browser=client(staff);
        assertThat(browser.call("POST","/api/auth/logout",Map.of()).statusCode()).isEqualTo(200);
        assertThat(browser.call("GET","/api/me",null).statusCode()).isEqualTo(401);
        var restricted=client(manager);
        assertThat(restricted.call("GET","/api/me",null).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE employees SET status='SUSPENDED' WHERE id=?",manager);
        assertThat(restricted.call("GET","/api/me",null).statusCode()).isEqualTo(401);
    }
    /** ADR-COM-001 keeps the session limits: an idle or too old session is refused at its next request. */
    @Test void idleAndMaximumSessionLimitsStillApply()throws Exception {
        var idle=client(staff);
        clock.advance(SessionBoundaryPolicy.IDLE_SECONDS);
        assertThat(idle.call("GET","/api/me",null).statusCode()).isEqualTo(401);
        clock.now.set(Instant.now());var active=client(manager);
        clock.advance(SessionBoundaryPolicy.MAX_SECONDS);
        setActivity(sessions,active.sessionId(),clock.instant().getEpochSecond()-1);
        assertThat(active.call("GET","/api/me",null).statusCode()).isEqualTo(401);
    }
    private <S extends org.springframework.session.Session> void setActivity(SessionRepository<S> repository,String id,long at) {
        S session=repository.findById(id);session.setAttribute(SessionBoundaryPolicy.LAST_ACTIVITY_AT,at);repository.save(session);
    }
}
