package com.openstudy.modules.locker;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.modules.locker.service.LockerAssignmentService;
import com.openstudy.modules.locker.service.LockerService;
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixtures and HTTP helpers of the locker tests. Today is the contract's own example day, 2026-11-01 in Korea, and only
 * the two locker services read this clock, so sessions keep living on real time.
 */
@MySqlIntegrationTest
abstract class LockerIntegrationSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @Autowired CustomerService customers;
    @Autowired LockerService lockerService;
    @Autowired LockerAssignmentService assignmentService;
    @LocalServerPort int port;

    final MutableClock clock = new MutableClock();
    Object lockerClock, assignmentClock;
    long owner, cashier, reader, plain, operator, outsider, twoBranches, first, second;
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>(), customerIds = new ArrayList<>();
    int lockerNumber;

    static class MutableClock extends Clock {
        volatile Instant fixed;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return fixed == null ? Instant.now() : fixed; }
    }

    @BeforeEach void setup() {
        Object lockers = AopTestUtils.getUltimateTargetObject(lockerService), assignments = AopTestUtils.getUltimateTargetObject(assignmentService);
        lockerClock = ReflectionTestUtils.getField(lockers, "clock");
        assignmentClock = ReflectionTestUtils.getField(assignments, "clock");
        ReflectionTestUtils.setField(lockers, "clock", clock);
        ReflectionTestUtils.setField(assignments, "clock", clock);
        at("2026-11-01");
        owner = person("OWNER", "사장");
        cashier = person("EMPLOYEE", "계산 직원"); reader = person("EMPLOYEE", "장부 직원"); plain = person("EMPLOYEE", "일반 직원");
        operator = person("EMPLOYEE", "운영 직원"); outsider = person("EMPLOYEE", "둘째지점 직원"); twoBranches = person("EMPLOYEE", "겸임 직원");
        first = branch("사물함 첫 지점"); second = branch("사물함 둘째 지점");
        member(cashier, first, "payments:write");
        member(reader, first, "payments:read");
        member(plain, first);
        member(operator, first, "operations:write");
        member(outsider, second, "payments:write", "operations:write");
        member(twoBranches, first, "payments:write");
        member(twoBranches, second, "payments:read");
    }

    @AfterEach void clean() {
        Object lockers = AopTestUtils.getUltimateTargetObject(lockerService), assignments = AopTestUtils.getUltimateTargetObject(assignmentService);
        ReflectionTestUtils.setField(lockers, "clock", lockerClock);
        ReflectionTestUtils.setField(assignments, "clock", assignmentClock);
        for (long customer : customerIds) {
            jdbc.update("DELETE FROM locker_assignment_charges WHERE assignment_id IN (SELECT id FROM locker_assignments WHERE customer_id=?)", customer);
            jdbc.update("DELETE FROM locker_assignments WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM payments WHERE customer_id=?", customer);
            jdbc.update("DELETE FROM customers WHERE id=?", customer);
        }
        String actors = people.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.update("DELETE FROM audit_events WHERE actor_employee_id IN (" + actors + ")");
        for (long id : branches) {
            jdbc.update("DELETE FROM lockers WHERE branch_id=?", id);
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", id);
            jdbc.update("DELETE g FROM membership_permission_grants g JOIN branch_memberships m ON m.id=g.membership_id WHERE m.branch_id=?", id);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branches WHERE id=?", id);
        }
        for (long id : people) {
            jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM employees WHERE id=?", id);
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------------------

    /** Ten in the morning in Korea on that day, an instant that falls on the same day in UTC. */
    void at(String day) { clock.fixed = OffsetDateTime.parse(day + "T10:00:00+09:00").toInstant(); }

    void atInstant(String instant) { clock.fixed = Instant.parse(instant); }

    long person(String role, String name) {
        String email = UUID.randomUUID() + "@locker.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,?,?,?,'ACTIVE')",
                email, passwords.encode("locker-test-password"), name, name, role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"), "locker-fixture");
        branches.add(Long.parseLong(made.id()));
        return Long.parseLong(made.id());
    }

    void member(long person, long branch, String... grants) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,'STAFF','ACTIVE')", person, branch);
        for (String grant : grants)
            jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id "
                    + "FROM branch_memberships m JOIN permission_definitions p ON p.code=? WHERE m.employee_id=? AND m.branch_id=?",
                    grant, person, branch);
    }

    long customer(long branch) {
        var body = new HashMap<String, Object>();
        body.put("registeredBranchId", Long.toString(branch)); body.put("name", "사물함 고객");
        body.put("phone", "010" + (10_000_000 + (int) (Math.random() * 89_999_999)));
        long id = Long.parseLong(customers.create(owner, "all", key(), body, "locker-fixture").id());
        customerIds.add(id);
        return id;
    }

    long locker(long branch, long rate) {
        var body = new LinkedHashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("lockerNo", "L-" + ++lockerNumber); body.put("ratePer4WeeksKrw", rate);
        return Long.parseLong(lockerService.create(owner, "all", key(), body, "locker-fixture").id());
    }

    static String key() { return UUID.randomUUID().toString(); }
    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    String text(String sql, Object... args) { return jdbc.queryForObject(sql, String.class, args); }

    long payments(long customer) { return count("SELECT COUNT(*) FROM payments WHERE customer_id=?", customer); }
    long assignments(long customer) { return count("SELECT COUNT(*) FROM locker_assignments WHERE customer_id=?", customer); }
    long charges(long assignment) { return count("SELECT COUNT(*) FROM locker_assignment_charges WHERE assignment_id=?", assignment); }
    long refunds(long customer) { return count("SELECT COUNT(*) FROM refunds f JOIN payments p ON p.id=f.payment_id WHERE p.customer_id=?", customer); }
    String status(long assignment) { return text("SELECT status FROM locker_assignments WHERE id=?", assignment); }
    String endDate(long assignment) { return text("SELECT DATE_FORMAT(end_date,'%Y-%m-%d') FROM locker_assignments WHERE id=?", assignment); }
    long rate(long locker) { return count("SELECT rate_per_4weeks_krw FROM lockers WHERE id=?", locker); }
    long lockerVersion(long locker) { return count("SELECT version FROM lockers WHERE id=?", locker); }
    long audits(String type, long id, String action) {
        return count("SELECT COUNT(*) FROM audit_events WHERE entity_type=? AND entity_id=? AND action=?", type, id, action);
    }

    static Map<String, Object> assignment(long branch, long locker, long customer, String start, String end) {
        var body = new LinkedHashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("lockerId", Long.toString(locker)); body.put("customerId", Long.toString(customer));
        body.put("startDate", start); body.put("endDate", end); body.put("method", "카드");
        return body;
    }

    static Map<String, Object> with(Map<String, Object> body, String field, Object value) {
        var copy = new LinkedHashMap<>(body);
        copy.put(field, value);
        return copy;
    }

    static Map<String, Object> failed(Map<String, Object> body) { return with(body, "mockOutcome", "FAILED"); }

    static Map<String, Object> extension(long version, String end) {
        var body = new LinkedHashMap<String, Object>();
        body.put("version", version); body.put("endDate", end); body.put("method", "카드");
        return body;
    }

    // ---- HTTP helpers ---------------------------------------------------------------------------------------

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(http(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", "locker-test-password"), null).statusCode()).isEqualTo(200);
        return new Session(client, json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString());
    }

    HttpResponse<String> http(HttpClient client, String method, String path, String csrf, Object body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (csrf != null) request.header("X-CSRF-Token", csrf);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    HttpResponse<String> get(Session session, String path) throws Exception { return http(session.client(), "GET", path, null, null, null); }

    HttpResponse<String> post(Session session, String path, Object body, String key) throws Exception {
        return http(session.client(), "POST", path, session.token(), body, key);
    }

    HttpResponse<String> patch(Session session, String path, Object body) throws Exception {
        return http(session.client(), "PATCH", path, session.token(), body, null);
    }

    HttpResponse<String> assign(Session session, Object body, String key) throws Exception { return post(session, "/api/locker-assignments", body, key); }

    HttpResponse<String> assign(Session session, Object body) throws Exception { return assign(session, body, key()); }

    HttpResponse<String> extend(Session session, long id, Object body, String key) throws Exception {
        return post(session, "/api/locker-assignments/" + id + "/extend", body, key);
    }

    HttpResponse<String> extend(Session session, long id, long version, String end) throws Exception {
        return extend(session, id, extension(version, end), key());
    }

    HttpResponse<String> giveBack(Session session, long id, long version) throws Exception {
        return post(session, "/api/locker-assignments/" + id + "/return", Map.of("version", version), null);
    }

    HttpResponse<String> applyRate(Session session, long branch, long rate, String key) throws Exception {
        return post(session, "/api/branches/" + branch + "/locker-rate", Map.of("ratePer4WeeksKrw", rate), key);
    }

    JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }

    JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return node(response);
    }

    JsonNode created(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return node(response);
    }

    long assigned(Session session, Map<String, Object> body) throws Exception { return created(assign(session, body)).get("id").asLong(); }

    void refused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(node(response).get("code").asString()).isEqualTo(code);
    }

    static Set<String> fields(JsonNode object) {
        var names = new HashSet<String>();
        object.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    // ---- real concurrency -----------------------------------------------------------------------------------

    /** Starts every call at the same instant on its own thread and returns all responses. */
    <T> List<T> race(List<Callable<T>> calls) throws Exception {
        var pool = Executors.newFixedThreadPool(calls.size());
        var ready = new CountDownLatch(calls.size());
        var start = new CountDownLatch(1);
        try {
            var running = new ArrayList<Future<T>>();
            for (var call : calls) running.add(pool.submit(() -> { ready.countDown(); start.await(); return call.call(); }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : running) results.add(future.get(90, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    static List<Integer> statuses(List<HttpResponse<String>> responses) { return responses.stream().map(HttpResponse::statusCode).toList(); }
}
