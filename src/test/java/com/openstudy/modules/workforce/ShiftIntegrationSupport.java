package com.openstudy.modules.workforce;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fixtures and HTTP helpers of the shift tests. Two branches: the first has a MANAGER, two staff members and the employee
 * who also works at the second; the second has its own MANAGER and a member of its own. Times are written in Seoul.
 */
@MySqlIntegrationTest
abstract class ShiftIntegrationSupport {
    static final String PASSWORD = "shift-test-password";
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;

    String passwordHash;
    long owner, manager, staff, colleague, worker, secondManager, outsider, first, second;
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>();

    @BeforeEach void setup() {
        passwordHash = passwords.encode(PASSWORD);
        owner = person("OWNER", "사장");
        manager = person("EMPLOYEE", "첫 지점 매니저"); staff = person("EMPLOYEE", "첫 지점 직원"); colleague = person("EMPLOYEE", "첫 지점 동료");
        worker = person("EMPLOYEE", "두 지점 직원"); secondManager = person("EMPLOYEE", "둘째 지점 매니저"); outsider = person("EMPLOYEE", "둘째 지점 직원");
        first = branch("근무 첫 지점"); second = branch("근무 둘째 지점");
        member(manager, first, "MANAGER"); member(staff, first, "STAFF"); member(colleague, first, "STAFF");
        member(worker, first, "STAFF"); member(worker, second, "STAFF");
        member(secondManager, second, "MANAGER"); member(outsider, second, "STAFF");
    }

    @AfterEach void clean() {
        for (long id : people) {
            jdbc.update("DELETE b FROM attendance_breaks b JOIN attendance_records a ON a.id=b.attendance_id WHERE a.employee_id=?", id);
            jdbc.update("DELETE FROM attendance_records WHERE employee_id=?", id);
            jdbc.update("DELETE e FROM leave_balance_events e JOIN leave_grants g ON g.id=e.leave_grant_id WHERE g.employee_id=?", id);
            jdbc.update("DELETE a FROM leave_allocations a JOIN leave_requests r ON r.id=a.leave_request_id WHERE r.employee_id=?", id);
            jdbc.update("DELETE FROM leave_requests WHERE employee_id=?", id);
            jdbc.update("DELETE FROM leave_grants WHERE employee_id=?", id);
            jdbc.update("DELETE e FROM shift_events e JOIN shifts s ON s.id=e.shift_id WHERE s.employee_id=?", id);
            jdbc.update("DELETE FROM shifts WHERE employee_id=?", id);
        }
        for (long id : branches) {
            jdbc.update("DELETE g FROM membership_permission_grants g JOIN branch_memberships m ON m.id=g.membership_id WHERE m.branch_id=?", id);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", id);
        }
        for (long id : people) {
            jdbc.update("DELETE FROM branch_memberships WHERE employee_id=?", id);
            jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM audit_events WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", Long.toString(id));
        }
        for (long id : branches) jdbc.update("DELETE FROM branches WHERE id=?", id);
        for (long id : people) jdbc.update("DELETE FROM employees WHERE id=?", id);
        people.clear(); branches.clear();
    }

    // ---- fixtures -------------------------------------------------------------------------------------------

    long person(String role, String name) {
        String email = UUID.randomUUID() + "@shift.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,?,?,?,'ACTIVE')",
                email, passwordHash, name, name, role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        String code = UUID.randomUUID().toString().substring(0, 20);
        jdbc.update("INSERT INTO branches(code,name,address,status) VALUES(?,?,'서울','ACTIVE')", code, name);
        long id = jdbc.queryForObject("SELECT id FROM branches WHERE code=?", Long.class, code);
        branches.add(id);
        return id;
    }

    void member(long person, long branch, String role) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,?,'ACTIVE')", person, branch, role);
    }

    void stop(long branch) { jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", branch); }

    void revoke(long person, long branch) {
        jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=? AND branch_id=?", person, branch);
    }

    /** A leave written directly, as M12 leaves it: whole days in Seoul, at one of the person's branches (V11, M12-D02). */
    long leave(long person, String startsOn, String endsOn, String days, String status) {
        jdbc.update("INSERT INTO leave_requests(employee_id,branch_id,request_year,starts_on,ends_on,requested_days,reason,status) "
                + "VALUES(?,(SELECT MIN(branch_id) FROM branch_memberships WHERE employee_id=?),?,?,?,?,?,?)",
                person, person, Integer.parseInt(startsOn.substring(0, 4)), startsOn, endsOn, new java.math.BigDecimal(days), "개인 사유 비공개", status);
        return jdbc.queryForObject("SELECT MAX(id) FROM leave_requests WHERE employee_id=?", Long.class, person);
    }

    /** An attendance as #84 would record it, written directly: the clock commands are not part of #83. */
    long attendance(long person, long branch, Long shift, String clockIn, String clockOut) {
        jdbc.update("INSERT INTO attendance_records(employee_id,branch_id,shift_id,clock_in_at,clock_out_at,status) VALUES(?,?,?,?,?,?)",
                person, branch, shift, sql(clockIn), clockOut == null ? null : sql(clockOut), clockOut == null ? "WORKING" : "LEFT");
        return jdbc.queryForObject("SELECT MAX(id) FROM attendance_records WHERE employee_id=?", Long.class, person);
    }

    void pause(long attendance, String from, String to) {
        jdbc.update("INSERT INTO attendance_breaks(attendance_id,started_at,ended_at) VALUES(?,?,?)", attendance, sql(from), to == null ? null : sql(to));
    }

    /** A Seoul wall-clock time such as "2026-11-04 09:00" as the UTC instant the API speaks. */
    static Instant seoul(String time) { return OffsetDateTime.parse(time.replace(' ', 'T') + (time.length() == 16 ? ":00" : "") + "+09:00").toInstant(); }

    static String at(String time) { return seoul(time).toString(); }

    /** The UTC DATETIME literal stored for a Seoul wall-clock time, as text so no driver time zone applies. */
    static String sql(String time) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneOffset.UTC).format(seoul(time));
    }

    static String key() { return UUID.randomUUID().toString(); }
    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    long shifts(long person) { return count("SELECT COUNT(*) FROM shifts WHERE employee_id=?", person); }
    long scheduled(long person) { return count("SELECT COUNT(*) FROM shifts WHERE employee_id=? AND status='SCHEDULED'", person); }

    static Map<String, Object> shift(long branch, long employee, String start, String end) {
        var body = new LinkedHashMap<String, Object>();
        body.put("branchId", Long.toString(branch)); body.put("employeeId", Long.toString(employee));
        body.put("startsAt", at(start)); body.put("endsAt", at(end));
        return body;
    }

    static Map<String, Object> shift(long branch, long employee, String start, String end, Object breakMinutes) {
        return with(shift(branch, employee, start, end), "plannedBreakMinutes", breakMinutes);
    }

    static Map<String, Object> with(Map<String, Object> body, String field, Object value) {
        var copy = new LinkedHashMap<>(body);
        copy.put(field, value);
        return copy;
    }

    static Map<String, Object> change(long version, Object... pairs) {
        var body = new LinkedHashMap<String, Object>();
        body.put("version", version);
        for (int index = 0; index < pairs.length; index += 2) body.put((String) pairs[index], pairs[index + 1]);
        return body;
    }

    // ---- HTTP helpers ---------------------------------------------------------------------------------------

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(http(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", PASSWORD), null).statusCode()).isEqualTo(200);
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

    HttpResponse<String> register(Session session, Object body, String key) throws Exception {
        return http(session.client(), "POST", "/api/shifts", session.token(), body, key);
    }

    HttpResponse<String> register(Session session, Object body) throws Exception { return register(session, body, key()); }

    HttpResponse<String> edit(Session session, long id, Object body) throws Exception {
        return http(session.client(), "PATCH", "/api/shifts/" + id, session.token(), body, null);
    }

    HttpResponse<String> cancel(Session session, long id, Object body) throws Exception {
        return http(session.client(), "POST", "/api/shifts/" + id + "/cancel", session.token(), body, null);
    }

    HttpResponse<String> cancel(Session session, long id, long version) throws Exception { return cancel(session, id, Map.of("version", version)); }

    // ---- attendance commands (#84): the path names no employee and no record ----------------------------------

    HttpResponse<String> clockIn(Session session, long branch, String key) throws Exception {
        return http(session.client(), "POST", "/api/me/attendance/clock-in", session.token(), Map.of("branchId", Long.toString(branch)), key);
    }

    HttpResponse<String> clockIn(Session session, long branch) throws Exception { return clockIn(session, branch, key()); }

    HttpResponse<String> breakStart(Session session, long version) throws Exception { return self(session, "break-start", Map.of("version", version)); }

    HttpResponse<String> breakEnd(Session session, long version) throws Exception { return self(session, "break-end", Map.of("version", version)); }

    HttpResponse<String> clockOut(Session session, long version) throws Exception { return self(session, "clock-out", Map.of("version", version)); }

    HttpResponse<String> self(Session session, String command, Object body) throws Exception {
        return http(session.client(), "POST", "/api/me/attendance/" + command, session.token(), body, null);
    }

    /** The requester's attendance in progress, or a JSON null. */
    JsonNode current(Session session) throws Exception { return ok(get(session, "/api/me/attendance/current")).get("attendance"); }

    long working(long person) { return count("SELECT COUNT(*) FROM attendance_records WHERE employee_id=? AND status='WORKING'", person); }

    JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }

    JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return node(response);
    }

    JsonNode created(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return node(response);
    }

    long registered(Session session, Map<String, Object> body) throws Exception { return created(register(session, body)).get("id").asLong(); }

    void refused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(node(response).get("code").asString()).as(response.body()).isEqualTo(code);
    }

    static Set<String> fields(JsonNode object) {
        var names = new HashSet<String>();
        object.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    static List<String> ids(JsonNode items) {
        var ids = new ArrayList<String>();
        for (var item : items) ids.add(item.get("id").asString());
        return ids;
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
