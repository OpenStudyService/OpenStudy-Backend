package com.openstudy.modules.chat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import static org.assertj.core.api.Assertions.assertThat;

/** Fixture and HTTP vocabulary of the channel tests: two branches, staff with every kind of relation to them. */
abstract class ChannelTestSupport {
    static final String PASSWORD = "channel-test-password";

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @LocalServerPort int port;

    long owner, managerA, managerB, staffA, staffA2, staffB, dual, outsider, restricted;
    long a, b;
    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>(), tasks = new ArrayList<>();
    final Map<Long, Session> sessions = new HashMap<>();

    @BeforeEach void setup() {
        owner = person("OWNER", "사장", "ACTIVE");
        managerA = person("EMPLOYEE", "가 관리자", "ACTIVE"); managerB = person("EMPLOYEE", "나 관리자", "ACTIVE");
        staffA = person("EMPLOYEE", "다 직원", "ACTIVE"); staffA2 = person("EMPLOYEE", "라 직원", "ACTIVE");
        staffB = person("EMPLOYEE", "마 직원", "ACTIVE"); dual = person("EMPLOYEE", "바 겸직", "ACTIVE");
        outsider = person("EMPLOYEE", "사 무소속", "ACTIVE"); restricted = person("EMPLOYEE", "아 제한", "SUSPENDED");
        a = branch("채널 A 지점"); b = branch("채널 B 지점");
        member(managerA, a, "MANAGER"); member(managerB, b, "MANAGER");
        member(staffA, a, "STAFF"); member(staffA2, a, "STAFF"); member(staffB, b, "STAFF");
        member(dual, a, "STAFF"); member(dual, b, "STAFF"); member(restricted, a, "STAFF");
    }

    @AfterEach void clean() {
        sessions.values().forEach(Session::close);
        var channels = jdbc.queryForList("SELECT DISTINCT conversation_id FROM conversation_members WHERE employee_id IN (" + marks(people) + ")",
                Long.class, people.toArray());
        for (long id : channels) {
            jdbc.update("DELETE FROM conversation_thread_reads WHERE conversation_id=?", id);
            jdbc.update("DELETE FROM messages WHERE conversation_id=? AND parent_message_id IS NOT NULL", id);
            jdbc.update("DELETE FROM messages WHERE conversation_id=?", id);
            jdbc.update("DELETE FROM conversation_members WHERE conversation_id=?", id);
            jdbc.update("DELETE FROM conversations WHERE id=?", id);
            jdbc.update("DELETE FROM audit_events WHERE entity_type='channels' AND entity_id=?", id);
        }
        jdbc.update("DELETE FROM conversations WHERE created_by IN (" + marks(people) + ")", people.toArray());
        for (long id : tasks) {
            jdbc.update("DELETE FROM task_events WHERE task_id=?", id);
            jdbc.update("DELETE FROM task_checklist_items WHERE task_id=?", id);
            jdbc.update("DELETE FROM work_tasks WHERE id=?", id);
        }
        for (long id : people) {
            jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", Long.toString(id));
            jdbc.update("DELETE FROM audit_events WHERE actor_employee_id=? OR (entity_type='employee' AND entity_id=?)", id, id);
            jdbc.update("DELETE FROM workspace_event_outbox WHERE target_employee_id=? OR (entity_type IN ('employee','employees') AND entity_id=?)", id, id);
            jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id=?", id);
        }
        for (long id : branches) {
            jdbc.update("DELETE FROM workspace_event_outbox WHERE branch_id=?", id);
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branches WHERE id=?", id);
        }
        for (long id : people) jdbc.update("UPDATE employees SET created_by=NULL,updated_by=NULL WHERE id=?", id);
        for (long id : people) jdbc.update("DELETE FROM employees WHERE id=?", id);
    }

    static String marks(List<Long> values) { return String.join(",", java.util.Collections.nCopies(values.size(), "?")); }

    // ---- fixtures -------------------------------------------------------------------------------------------

    long person(String role, String name, String status) {
        String email = UUID.randomUUID() + "@channel.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,?,?,?,?)",
                email, passwords.encode(PASSWORD), name, name, role, status);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    long branch(String name) {
        var hours = IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        var made = branchService.create(owner, UUID.randomUUID().toString(), new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"), "channel-fixture");
        branches.add(Long.parseLong(made.id()));
        return Long.parseLong(made.id());
    }

    void member(long person, long branch, String role) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,?,'ACTIVE')", person, branch, role);
    }

    // ---- http ---------------------------------------------------------------------------------------------

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        var response = http(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", PASSWORD), null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return new Session(client, json.readTree(http(client, "GET", "/api/auth/csrf", null, null, null).body()).get("token").asString());
    }

    /** One logged-in session per person for the length of a test. */
    Session as(long person) throws Exception {
        var session = sessions.get(person);
        if (session == null) { session = login(person); sessions.put(person, session); }
        return session;
    }

    HttpResponse<String> http(HttpClient client, String method, String path, String csrf, Object body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (csrf != null) request.header("X-CSRF-Token", csrf);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(long person, String path) throws Exception {
        var session = as(person);
        return http(session.client(), "GET", path, null, null, null);
    }

    HttpResponse<String> write(long person, String method, String path, Object body) throws Exception {
        var session = as(person);
        return http(session.client(), method, path, session.token(), body, null);
    }

    /** For JSON a client library would refuse to produce, such as an escaped unpaired surrogate. */
    HttpResponse<String> writeRaw(long person, String path, String body) throws Exception {
        var session = as(person);
        return session.client().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-CSRF-Token", session.token()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    Map<String, Object> channelBody(String kind, Long branch, String name, long... members) {
        var body = new LinkedHashMap<String, Object>();
        body.put("kind", kind); body.put("name", name); body.put("memberIds", ids(members));
        if (branch != null) body.put("branchId", Long.toString(branch));
        return body;
    }

    HttpResponse<String> createRaw(long person, Map<String, Object> body, String key) throws Exception {
        var session = as(person);
        return http(session.client(), "POST", "/api/channels", session.token(), body, key);
    }

    JsonNode channel(long person, Long branch, String name, long... members) throws Exception {
        var response = createRaw(person, channelBody(branch == null ? "전체" : "지점", branch, name, members), key());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return node(response);
    }

    long channelId(long person, Long branch, String name, long... members) throws Exception {
        return id(channel(person, branch, name, members));
    }

    HttpResponse<String> send(long person, long channel, String key, String text, Object parent) throws Exception {
        return sendWith(as(person), channel, key, text, parent);
    }

    HttpResponse<String> sendWith(Session session, long channel, String key, String text, Object parent) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("clientMessageKey", key); body.put("text", text);
        if (parent != null) body.put("parentId", parent.toString());
        return http(session.client(), "POST", "/api/channels/" + channel + "/messages", session.token(), body, null);
    }

    long sent(long person, long channel, String text) throws Exception { return reply(person, channel, text, null); }

    long reply(long person, long channel, String text, Long parent) throws Exception {
        var response = send(person, channel, key(), text, parent);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return id(node(response));
    }

    HttpResponse<String> read(long person, long channel, Object message) throws Exception {
        return write(person, "POST", "/api/channels/" + channel + "/read", Map.of("messageId", message.toString()));
    }

    HttpResponse<String> replaceMembers(long person, long channel, long version, long... members) throws Exception {
        return write(person, "PUT", "/api/channels/" + channel + "/members", Map.of("version", version, "employeeIds", ids(members)));
    }

    JsonNode detail(long person, long channel) throws Exception { return ok(get(person, "/api/channels/" + channel)); }

    long version(long channel) { return count("SELECT version FROM conversations WHERE id=?", channel) + 1; }

    JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return node(response);
    }

    JsonNode node(HttpResponse<String> response) { return json.readTree(response.body()); }

    String code(HttpResponse<String> response) { return node(response).get("code").asString(); }

    void refused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        if (code != null) assertThat(code(response)).isEqualTo(code);
    }

    /** Message IDs of a feed in the order the API returned them. */
    List<Long> items(JsonNode feed) {
        var result = new ArrayList<Long>();
        for (var item : feed.get("items")) result.add(id(item));
        return result;
    }

    static long id(JsonNode value) { return Long.parseLong(value.get("id").asString()); }

    List<String> memberNames(long person, long channel) throws Exception {
        var result = new ArrayList<String>();
        for (var item : ok(get(person, "/api/channels/" + channel + "/members")).get("items")) result.add(item.get("employee").get("name").asString());
        return result;
    }

    List<String> channelNames(long person, String query) throws Exception {
        var result = new ArrayList<String>();
        for (var item : ok(get(person, "/api/channels" + query)).get("items")) result.add(item.get("name").asString());
        return result;
    }

    Long leftAtIsSet(long channel, long person) {
        return count("SELECT COUNT(*) FROM conversation_members WHERE conversation_id=? AND employee_id=? AND left_at IS NOT NULL", channel, person);
    }

    long count(String sql, Object... values) { return jdbc.queryForObject(sql, Long.class, values); }

    static List<String> ids(long... values) { return Arrays.stream(values).mapToObj(Long::toString).toList(); }

    static String key() { return UUID.randomUUID().toString(); }

    /** Through the employee API, so the end of participation runs in the command's own transaction. */
    HttpResponse<String> revoke(long target, long branch) throws Exception {
        var current = ok(get(owner, "/api/employees/" + target));
        for (var membership : current.get("memberships")) {
            if (!membership.get("branchId").asString().equals(Long.toString(branch))) continue;
            var body = new LinkedHashMap<String, Object>();
            body.put("employeeVersion", current.get("version").asLong()); body.put("version", membership.get("version").asLong());
            body.put("role", membership.get("role").asString()); body.put("status", "회수"); body.put("permissions", List.of());
            return write(owner, "PUT", "/api/employees/" + target + "/memberships/" + branch, body);
        }
        throw new IllegalStateException("No membership at the branch");
    }

    HttpResponse<String> accountStatus(long actor, long target, String status) throws Exception {
        var current = ok(get(actor, "/api/employees/" + target));
        return write(actor, "PATCH", "/api/employees/" + target, Map.of("version", current.get("version").asLong(), "status", status));
    }

    <T> List<T> race(List<Callable<T>> calls) throws Exception {
        var pool = Executors.newFixedThreadPool(calls.size());
        try {
            var ready = new CountDownLatch(calls.size());
            var start = new CountDownLatch(1);
            var running = new ArrayList<Future<T>>();
            for (var call : calls) running.add(pool.submit(() -> { ready.countDown(); start.await(); return call.call(); }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : running) results.add(future.get(60, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    static List<Integer> statuses(List<HttpResponse<String>> responses) { return responses.stream().map(HttpResponse::statusCode).sorted().toList(); }
}
