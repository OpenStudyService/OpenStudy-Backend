package com.openstudy.modules.employee;

import java.util.*;
import java.net.*;
import java.net.http.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.support.database.MySqlIntegrationTest;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.ListQuery;
import com.openstudy.modules.employee.service.EmployeeManagementService;
import com.openstudy.modules.authorization.service.PermissionPresetService;
import static org.assertj.core.api.Assertions.*;

@MySqlIntegrationTest
class EmployeeManagementIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired EmployeeManagementService employees;
    @Autowired PermissionPresetService presets;
    @LocalServerPort int port;
    private final List<Long> people=new ArrayList<>(), branches=new ArrayList<>(), createdPresets=new ArrayList<>();
    private long owner,manager,staff,other,first,second;
    private String hash;
    private ListQuery query() { return ListQuery.parse(1,100,null,null,null,Set.of("createdAt","name"),"createdAt"); }
    @BeforeEach void fixture() {
        hash=encoder.encode("employee-test-password");
        owner=person("OWNER","ACTIVE"); manager=person("EMPLOYEE","ACTIVE"); staff=person("EMPLOYEE","ACTIVE"); other=person("EMPLOYEE","ACTIVE");
        first=branch(); second=branch();
        membership(manager,first,"MANAGER","ACTIVE"); membership(staff,first,"STAFF","ACTIVE"); membership(staff,second,"STAFF","ACTIVE"); membership(other,second,"STAFF","ACTIVE");
        jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id FROM branch_memberships m JOIN permission_definitions p ON p.code='people:manage' WHERE m.employee_id=?",manager);
    }
    @AfterEach void cleanup() {
        for(long id:people) {
            jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?",Long.toString(id));
            jdbc.update("DELETE FROM audit_events WHERE actor_employee_id=? OR (entity_type='employee' AND entity_id=?)",id,id);
            jdbc.update("DELETE FROM workspace_event_outbox WHERE target_employee_id=? OR (entity_type IN ('employee','employees') AND entity_id=?)",id,id);
            jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id=?",id);
        }
        for(long id:createdPresets) {
            jdbc.update("DELETE FROM workspace_event_outbox WHERE entity_type='permission-presets' AND entity_id=?",id);
            jdbc.update("DELETE FROM permission_presets WHERE id=?",id);
        }
        for(long id:people) jdbc.update("DELETE FROM branch_memberships WHERE employee_id=?",id);
        for(long id:people) jdbc.update("UPDATE employees SET created_by=NULL,updated_by=NULL WHERE id=?",id);
        for(long id:people) jdbc.update("DELETE FROM employees WHERE id=?",id);
        for(long id:branches) jdbc.update("DELETE FROM branches WHERE id=?",id);
        people.clear(); branches.clear(); createdPresets.clear();
    }
    private long person(String role,String status) {
        String email=UUID.randomUUID()+"@employee.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES (?,?,'직원','별명',?,?)",email,hash,role,status);
        long id=jdbc.queryForObject("SELECT id FROM employees WHERE email=?",Long.class,email); people.add(id); return id;
    }
    private long branch() {
        String code=UUID.randomUUID().toString().substring(0,20);
        jdbc.update("INSERT INTO branches(code,name,address) VALUES (?,'직원 지점','서울')",code);
        long id=jdbc.queryForObject("SELECT id FROM branches WHERE code=?",Long.class,code); branches.add(id); return id;
    }
    private void membership(long employee,long branch,String role,String status) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES (?,?,?,?)",employee,branch,role,status);
    }
    private Map<String,Object> membershipBody(long employee,long branch,String role,String status,List<String> grants) {
        var current=employees.get(owner,employee,"all");
        var membership=current.memberships().stream().filter(m->m.branchId().equals(Long.toString(branch))).findFirst().orElseThrow();
        return new HashMap<>(Map.of("employeeVersion",current.version(),"version",membership.version(),"role",role,"status",status,"permissions",grants));
    }
    @Test void managementScopeFiltersTotalAndEveryMembershipAndOwnerSeesUnassignedAccounts() {
        var result=employees.list(manager,"all",query(),null);
        assertThat(result.meta().total()).isEqualTo(2);
        assertThat(result.items()).extracting(e->Long.parseLong(e.id())).containsExactlyInAnyOrder(manager,staff);
        assertThat(result.items()).allSatisfy(e->assertThat(e.memberships()).allSatisfy(m->assertThat(m.branchId()).isEqualTo(Long.toString(first))));
        assertThatThrownBy(()->employees.get(manager,other,"all")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->employees.list(manager,Long.toString(second),query(),null)).isInstanceOf(AccessDeniedException.class);
        assertThat(employees.list(owner,"all",query(),null).items()).extracting(e->Long.parseLong(e.id())).contains(owner,other);
        assertThatThrownBy(()->employees.list(staff,"all",query(),null)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void membershipPutChecksBothVersionsPreservesOtherBranchAndWritesCredentialFreeAudit() {
        var body=membershipBody(staff,first,"staff","활성",List.of("members:write"));
        var result=employees.membership(owner,staff,first,"all",body,"audit-request");
        assertThat(result.employeeVersion()).isEqualTo(2); assertThat(result.membership().version()).isEqualTo(2);
        var untouched=employees.get(owner,staff,"all").memberships().stream().filter(m->m.branchId().equals(Long.toString(second))).findFirst().orElseThrow();
        assertThat(untouched.version()).isEqualTo(1); assertThat(untouched.permissions()).isEmpty();
        assertThatThrownBy(()->employees.membership(owner,staff,first,"all",body,"stale")).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).error("x").code()).isEqualTo("VERSION_CONFLICT"));
        var staleMembership=membershipBody(staff,first,"staff","활성",List.of()); staleMembership.put("version",1L);
        assertThatThrownBy(()->employees.membership(owner,staff,first,"all",staleMembership,"stale-member")).isInstanceOf(ApiException.class);
        String audit=jdbc.queryForObject("SELECT CONCAT(before_json,after_json) FROM audit_events WHERE request_id='audit-request'",String.class);
        assertThat(audit).contains("members:write","employeeVersion").doesNotContain(hash,"password","token");
        assertThat(jdbc.queryForObject("SELECT actor_employee_id FROM audit_events WHERE request_id='audit-request'",Long.class)).isEqualTo(owner);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_event_outbox WHERE target_employee_id=? AND event_type='AUTHORIZATION_CHANGED'",Long.class,staff)).isEqualTo(1);
    }
    @Test void managerMayEditOnlyOtherStaffAndStaffCannotReceiveAdminOnlyGrants() {
        assertThatThrownBy(()->employees.membership(manager,manager,first,"all",membershipBody(manager,first,"admin","활성",List.of()),"self")).isInstanceOf(AccessDeniedException.class);
        long another=person("EMPLOYEE","ACTIVE"); membership(another,first,"MANAGER","ACTIVE");
        assertThatThrownBy(()->employees.membership(manager,another,first,"all",membershipBody(another,first,"staff","활성",List.of()),"demote")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->employees.membership(manager,staff,first,"all",membershipBody(staff,first,"admin","활성",List.of()),"promote")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->employees.membership(owner,staff,first,"all",membershipBody(staff,first,"staff","활성",List.of("people:manage")),"invalid-grant")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->employees.membership(owner,staff,first,"all",membershipBody(staff,first,"staff","활성",List.of("unknown:write")),"unknown-grant")).isInstanceOf(ApiException.class);
        assertThat(employees.membership(manager,staff,first,"all",membershipBody(staff,first,"staff","활성",List.of("operations:write")),"staff-edit").membership().permissions()).containsExactly("operations:write");
        assertThat(employees.membership(owner,staff,first,"all",membershipBody(staff,first,"admin","활성",List.of("people:manage")),"owner-promote").membership().role()).isEqualTo("admin");
    }
    @Test void pendingOrRevokedMembershipCannotBypassInvitationAndFailedWritesLeaveNoOutbox() {
        jdbc.update("UPDATE branch_memberships SET status='PENDING' WHERE employee_id=? AND branch_id=?",staff,first);
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM workspace_event_outbox",Long.class);
        assertThatThrownBy(()->employees.membership(owner,staff,first,"all",membershipBody(staff,first,"staff","활성",List.of()),"pending")).isInstanceOf(ApiException.class);
        jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=? AND branch_id=?",staff,first);
        assertThatThrownBy(()->employees.membership(owner,staff,first,"all",membershipBody(staff,first,"staff","활성",List.of()),"revoked")).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_event_outbox",Long.class)).isEqualTo(before);
        long pending=person("EMPLOYEE","PENDING");
        assertThatThrownBy(()->employees.update(owner,pending,Map.of("version",1,"status","활성"),"pending-account")).isInstanceOf(ApiException.class);
    }
    @Test void presetCopyIsIndependentAndSystemPresetsAreExactAndImmutable() {
        var system=presets.list(owner,query()).items().stream().filter(p->p.system()).toList();
        assertThat(system).filteredOn(p->p.role().equals("staff")).singleElement().satisfies(p->assertThat(p.permissions()).isEmpty());
        assertThat(system).filteredOn(p->p.role().equals("admin")).singleElement().satisfies(p->assertThat(p.permissions()).hasSize(9));
        var preset=presets.create(owner,Map.of("name","검증-"+UUID.randomUUID(),"role","staff","permissions",List.of("members:write")),UUID.randomUUID().toString(),"create-preset");
        long id=Long.parseLong(preset.id()); createdPresets.add(id);
        var body=membershipBody(staff,first,"staff","활성",List.of("members:write","operations:write")); body.put("presetId",preset.id());
        employees.membership(owner,staff,first,"all",body,"copy-preset");
        presets.update(owner,id,Map.of("version",1,"name",preset.name(),"permissions",List.of()),"update-preset");
        assertThat(employees.get(owner,staff,"all").memberships().getFirst().permissions()).containsExactly("members:write","operations:write");
        var defaultPreset=system.getFirst();
        assertThatThrownBy(()->presets.update(owner,Long.parseLong(defaultPreset.id()),Map.of("version",defaultPreset.version(),"name","변경","permissions",List.of()),"system")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->presets.create(manager,Map.of("name","위조","role","staff","permissions",List.of()),"key","manager-preset")).isInstanceOf(AccessDeniedException.class);
    }
    @Test void presetIdempotencyReturnsOriginalResponseAndRejectsChangedBody() {
        String key=UUID.randomUUID().toString(); var body=Map.<String,Object>of("name","멱등-"+UUID.randomUUID(),"role","staff","permissions",List.of());
        var one=presets.create(owner,body,key,"idempotent"); createdPresets.add(Long.parseLong(one.id()));
        assertThat(presets.create(owner,new TreeMap<>(body),key,"replay")).isEqualTo(one);
        assertThatThrownBy(()->presets.create(owner,body,"short","short-key")).isInstanceOf(ApiException.class);
        var different=new HashMap<>(body); different.put("name","다른 이름");
        assertThatThrownBy(()->presets.create(owner,different,key,"changed")).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE entity_type='permission-preset' AND entity_id=?",Long.class,Long.parseLong(one.id()))).isEqualTo(1);
    }
    @Test void concurrentSavesWithSameVersionsHaveOneWinnerAndOneConflict() throws Exception {
        var body=membershipBody(staff,first,"staff","활성",List.of("members:write")); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<String> save=()-> { start.await(10,TimeUnit.SECONDS); try { employees.membership(owner,staff,first,"all",body,"concurrent"); return "saved"; } catch(ApiException e) { return e.error("x").code(); } };
            var one=pool.submit(save); var two=pool.submit(save); start.countDown();
            assertThat(List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder("saved","VERSION_CONFLICT");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE request_id='concurrent'",Long.class)).isEqualTo(1);
    }
    @Test void concurrentPresetEditAndCopySaveFinalPermissionsWithoutRetroactiveUpdates() throws Exception {
        var preset=presets.create(owner,Map.of("name","경합-"+UUID.randomUUID(),"role","staff","permissions",List.of("members:write")),UUID.randomUUID().toString(),"race-preset-create");
        long id=Long.parseLong(preset.id()); createdPresets.add(id);
        var body=membershipBody(staff,first,"staff","활성",List.of("operations:write")); body.put("presetId",preset.id());
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()-> { if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); return presets.update(owner,id,Map.of("version",1,"name",preset.name(),"permissions",List.of()),"race-preset-edit"); });
            var two=pool.submit(()-> { if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); return employees.membership(owner,staff,first,"all",body,"race-preset-copy"); });
            start.countDown(); assertThat(one.get(15,TimeUnit.SECONDS).permissions()).isEmpty();
            assertThat(two.get(15,TimeUnit.SECONDS).membership().permissions()).containsExactly("operations:write");
        }
        assertThat(employees.get(owner,staff,"all").memberships().getFirst().permissions()).containsExactly("operations:write");
    }
    @Test void lastOwnerStatusChangeRollsBackAndUnknownAccountFieldsAreRejected() {
        assertThatThrownBy(()->employees.update(owner,owner,Map.of("version",1,"status","이용 제한"),"last-owner")).isInstanceOf(ApiException.class).satisfies(e->assertThat(((ApiException)e).error("x").code()).isEqualTo("LAST_ADMIN_REQUIRED"));
        for(String field:List.of("email","owner","memberships")) assertThatThrownBy(()->employees.update(owner,staff,Map.of("version",1,field,"forged"),"unknown")).isInstanceOf(ApiException.class);
        assertThat(employees.get(owner,owner,"all").status()).isEqualTo("활성");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE request_id='last-owner'",Long.class)).isZero();
    }
    private final class Session implements AutoCloseable {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String csrf;
        String idempotencyKey;
        Session(long employee) throws Exception {
            csrf=json.readTree(call("GET","/api/auth/csrf",null).body()).get("token").asString();
            String email=jdbc.queryForObject("SELECT email FROM employees WHERE id=?",String.class,employee);
            assertThat(call("POST","/api/auth/login",Map.of("email",email,"password","employee-test-password")).statusCode()).isEqualTo(200);
            csrf=json.readTree(call("GET","/api/auth/csrf",null).body()).get("token").asString();
        }
        HttpResponse<String> call(String method,String path,Object body) throws Exception {
            var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
            if(csrf!=null) builder.header("X-CSRF-Token",csrf);
            if(idempotencyKey!=null) builder.header("Idempotency-Key",idempotencyKey);
            if(body!=null) builder.header("Content-Type","application/json");
            builder.method(method,body==null ? HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        }
        public void close() { client.close(); }
    }
    @Test void realHttpContractsEnforceRolesCsrfScopeUnknownFieldsAndAccountSessionRevocation() throws Exception {
        try(var ownerSession=new Session(owner); var staffSession=new Session(staff); var managerSession=new Session(manager)) {
            var list=managerSession.call("GET","/api/employees?scope=all",null);
            assertThat(list.statusCode()).isEqualTo(200); assertThat(json.readTree(list.body()).get("meta").get("total").asLong()).isEqualTo(2);
            assertThat(list.body()).doesNotContain(hash,"password_hash").contains("memberships","version");
            assertThat(staffSession.call("GET","/api/employees",null).statusCode()).isEqualTo(403);
            var definitions=staffSession.call("GET","/api/permissions",null);
            assertThat(definitions.statusCode()).isEqualTo(200);
            assertThat(json.readTree(definitions.body()).size()).isEqualTo(9);
            var presetBody=Map.of("name","HTTP-"+UUID.randomUUID(),"role","staff","permissions",List.of());
            assertThat(ownerSession.call("POST","/api/permission-presets",presetBody).statusCode()).isEqualTo(422);
            ownerSession.idempotencyKey=UUID.randomUUID().toString();
            var created=ownerSession.call("POST","/api/permission-presets",presetBody);
            assertThat(created.statusCode()).isEqualTo(201);
            createdPresets.add(Long.parseLong(json.readTree(created.body()).get("id").asString()));
            assertThat(ownerSession.call("POST","/api/permission-presets",presetBody).body()).isEqualTo(created.body());
            assertThat(ownerSession.call("POST","/api/permission-presets",Map.of("name","다른 입력","role","staff","permissions",List.of())).statusCode()).isEqualTo(409);
            ownerSession.idempotencyKey=null;
            assertThat(managerSession.call("PATCH","/api/employees/"+staff,Map.of("version",1,"name","변경")).statusCode()).isEqualTo(403);
            assertThat(ownerSession.call("PATCH","/api/employees/"+staff,Map.of("version",1,"email","forged@example.com")).statusCode()).isEqualTo(422);
            String saved=ownerSession.csrf; ownerSession.csrf="bad";
            assertThat(ownerSession.call("PATCH","/api/employees/"+staff,Map.of("version",1,"name","변경")).statusCode()).isEqualTo(403); ownerSession.csrf=saved;
            assertThat(managerSession.call("PUT","/api/employees/"+staff+"/memberships/"+second+"?scope="+first,membershipBody(staff,second,"staff","활성",List.of())).statusCode()).isEqualTo(403);
            var restrict=ownerSession.call("PATCH","/api/employees/"+staff,Map.of("version",1,"status","이용 제한"));
            assertThat(restrict.statusCode()).isEqualTo(200);
            assertThat(staffSession.call("GET","/api/me",null).statusCode()).isEqualTo(401);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?",Long.class,Long.toString(staff))).isZero();
            var malformed=ownerSession.call("GET","/api/employees?page=0",null); assertThat(malformed.statusCode()).isEqualTo(422);
            assertThat(malformed.body()).contains("requestId");
        }
    }
}
