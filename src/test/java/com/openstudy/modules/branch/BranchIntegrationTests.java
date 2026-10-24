package com.openstudy.modules.branch;

import java.util.*;
import java.net.*;
import java.net.http.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.support.database.MySqlIntegrationTest;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.modules.branch.dto.*;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.common.exception.ApiException;
import static org.assertj.core.api.Assertions.*;

@MySqlIntegrationTest
class BranchIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService service;
    @Autowired TransactionTemplate transactions;
    @LocalServerPort int port;
    long owner,manager,staff,first,second;
    List<Long> people=new ArrayList<>(),branches=new ArrayList<>();
    @BeforeEach void setup() {
        owner=person("OWNER");manager=person("EMPLOYEE");staff=person("EMPLOYEE");
        first=create("첫 지점").id().transform(Long::parseLong);second=Long.parseLong(create("다른 지점").id());
        for(long person:List.of(manager,staff)) jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,?,'ACTIVE')",person,first,person==manager?"MANAGER":"STAFF");
        for(long person:List.of(manager,staff)) jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id FROM branch_memberships m JOIN permission_definitions p ON p.code='operations:write' WHERE m.employee_id=?",person);
    }
    long person(String role) {
        String email=UUID.randomUUID()+"@branch.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,'지점','지점',?,'ACTIVE')",email,passwords.encode("branch-test-password"),role);
        long id=jdbc.queryForObject("SELECT id FROM employees WHERE email=?",Long.class,email);people.add(id);return id;
    }
    List<WeeklyHours> hours() { return java.util.stream.IntStream.rangeClosed(1,7).mapToObj(day->new WeeklyHours(day,false,0,1440)).toList(); }
    BranchRequests.Create body(String name) { return new BranchRequests.Create(name,"서울","02-123",hours(),"운영 중"); }
    Branch create(String name) {
        var b=service.create(owner,UUID.randomUUID().toString(),body(name),"branch-fixture");branches.add(Long.parseLong(b.id()));return b;
    }
    @AfterEach void clean() {
        for(long id:branches) {
            jdbc.update("DELETE FROM workspace_event_outbox WHERE branch_id=?",id);
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?",id);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?",id);
            jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id=?",id);
            jdbc.update("DELETE FROM branches WHERE id=?",id);
        }
        for(long id:people) {jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id=?",id);jdbc.update("DELETE FROM employees WHERE id=?",id);}
    }
    record Session(HttpClient client,String token) implements AutoCloseable { public void close(){client.close();} }
    Session login(long person) throws Exception {
        var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String token=json.readTree(http(client,"GET","/api/auth/csrf",null,null,null).body()).get("token").asString();
        String email=jdbc.queryForObject("SELECT email FROM employees WHERE id=?",String.class,person);
        assertThat(http(client,"POST","/api/auth/login",token,Map.of("email",email,"password","branch-test-password"),null).statusCode()).isEqualTo(200);
        return new Session(client,json.readTree(http(client,"GET","/api/auth/csrf",null,null,null).body()).get("token").asString());
    }
    HttpResponse<String> http(HttpClient client,String method,String path,String token,Object body,String key) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
        if(token!=null) req.header("X-CSRF-Token",token);
        if(key!=null) req.header("Idempotency-Key",key);
        if(body!=null) req.header("Content-Type","application/json");
        return client.send(req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> request(Session s,String method,String path,Object body) throws Exception {return http(s.client(),method,path,s.token(),body,null);}
    @Test void httpRoleScopeVersionAndStoppedHistory() throws Exception {
        try(var s=login(staff);var m=login(manager);var o=login(owner)) {
            var list=request(s,"GET","/api/branches?scope=all",null);
            assertThat(list.statusCode()).isEqualTo(200);assertThat(json.readTree(list.body()).at("/meta/total").asLong()).isEqualTo(1);
            assertThat(json.readTree(list.body()).at("/items/0/weeklyHours").size()).isEqualTo(7);
            assertThat(request(s,"GET","/api/branches/"+second,null).statusCode()).isEqualTo(403);
            assertThat(request(s,"PATCH","/api/branches/"+first,Map.of("version",1,"name","불가")).statusCode()).isEqualTo(403);
            assertThat(http(m.client(),"POST","/api/branches",m.token(),body("생성 불가"),UUID.randomUUID().toString()).statusCode()).isEqualTo(403);
            var changed=request(m,"PATCH","/api/branches/"+first,Map.of("version",1,"name","새 이름"));
            assertThat(changed.statusCode()).isEqualTo(200);assertThat(json.readTree(changed.body()).get("version").asLong()).isEqualTo(2);
            var conflict=request(m,"PATCH","/api/branches/"+first,Map.of("version",1,"name","낡은 입력"));
            assertThat(conflict.statusCode()).isEqualTo(409);assertThat(conflict.body()).contains("latestVersion","reloadUrl","requestId");
            assertThat(request(m,"POST","/api/branches/"+first+"/status",Map.of("version",2,"status","운영 중지")).statusCode()).isEqualTo(200);
            assertThat(request(s,"GET","/api/branches/"+first,null).body()).contains("운영 중지","새 이름");
            assertThat(request(m,"PATCH","/api/branches/"+second,Map.of("version",1,"name","우회")).statusCode()).isEqualTo(403);
            assertThat(request(o,"GET","/api/branches?scope=all&sort=password",null).statusCode()).isEqualTo(422);
            assertThat(request(o,"GET","/api/branches/"+first+"?scope="+second,null).statusCode()).isEqualTo(404);
            assertThat(request(o,"PATCH","/api/branches/"+first+"?scope="+second,Map.of("version",3,"name","범위 밖")).statusCode()).isEqualTo(404);
            assertThat(json.readTree(request(s,"GET","/api/branches?q=does-not-exist",null).body()).at("/meta/total").asLong()).isZero();
            jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=?",staff);
            assertThat(json.readTree(request(s,"GET","/api/branches",null).body()).get("items").size()).isZero();
            assertThat(request(s,"GET","/api/branches/"+first,null).statusCode()).isEqualTo(403);
        }
    }
    @Test void httpCreationReusesSavedResponseAndValidatesEveryDayAndField() throws Exception {
        try(var o=login(owner)) {
            String key=UUID.randomUUID().toString();
            var made=http(o.client(),"POST","/api/branches",o.token(),body("신규"),key);
            assertThat(made.statusCode()).isEqualTo(201);long id=json.readTree(made.body()).get("id").asLong();branches.add(id);
            assertThat(made.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(http(o.client(),"POST","/api/branches",o.token(),body("신규"),key).body()).isEqualTo(made.body());
            assertThat(http(o.client(),"POST","/api/branches",o.token(),body("다른 입력"),key).statusCode()).isEqualTo(409);
            assertThat(request(o,"PATCH","/api/branches/"+id,Map.of("version",1,"status","운영 중지")).statusCode()).isEqualTo(422);
            assertThat(request(o,"PATCH","/api/branches/"+id,Map.of("version",1)).statusCode()).isEqualTo(422);
            var explicitNull=new HashMap<String,Object>();explicitNull.put("version",1);explicitNull.put("name",null);
            assertThat(request(o,"PATCH","/api/branches/"+id,explicitNull).statusCode()).isEqualTo(422);
            var invalid=new ArrayList<>(hours());invalid.set(6,invalid.getFirst());
            assertThat(request(o,"PATCH","/api/branches/"+id,Map.of("version",1,"weeklyHours",invalid)).statusCode()).isEqualTo(422);
            invalid=new ArrayList<>(hours());invalid.set(0,new WeeklyHours(1,false,600,1800));
            assertThat(request(o,"PATCH","/api/branches/"+id,Map.of("version",1,"weeklyHours",invalid)).statusCode()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM branch_weekly_hours WHERE branch_id=?",Long.class,id)).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT request_id FROM audit_events WHERE branch_id=? AND action='CREATED'",String.class,id)).isEqualTo(made.headers().firstValue("X-Request-ID").orElseThrow());
        }
    }
    @Test void concurrentSameKeyCreatesOnceAndDifferentInputConflicts() throws Exception {
        String key=UUID.randomUUID().toString();CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Branch> call=()->{start.await();return service.create(owner,key,body("동시 등록"),"concurrency");};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();
            var one=a.get(15,TimeUnit.SECONDS);var two=b.get(15,TimeUnit.SECONDS);branches.add(Long.parseLong(one.id()));
            assertThat(two).isEqualTo(one);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=?",Long.class,one.id())).isEqualTo(1);
            assertThatThrownBy(()->service.create(owner,key,body("다른 값"),"different")).isInstanceOf(ApiException.class);
        }
    }
    @Test void scheduleAuditOutboxAndIdempotencyRollbackTogether() {
        String key=UUID.randomUUID().toString();long count=jdbc.queryForObject("SELECT COUNT(*) FROM branches",Long.class);
        transactions.executeWithoutResult(tx->{service.create(owner,key,body("롤백"),"rollback");tx.setRollbackOnly();});
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM branches",Long.class)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE request_id='rollback'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key=?",Long.class,key)).isZero();
        var schedule=new ArrayList<>(hours());schedule.set(0,new WeeklyHours(1,true,0,1440));
        assertThatThrownBy(()->service.update(owner,first,new BranchRequests.Update(1L,null,null,null,schedule),"invalid")).isInstanceOf(ApiException.class);
        assertThat(service.get(owner,first).version()).isEqualTo(1);
    }
}
