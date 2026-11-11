package com.openstudy.modules.account;

import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M14 milestone check over HTTP and MySQL (account-collab-api.md): password change, administrator reset while reset mail
 * is off (the test default), profile photos and who may see them, and the OWNER-only audit search.
 */
@MySqlIntegrationTest
class AccountSecurityIntegrationTests {
    static final String PASSWORD = "account-check-password";
    static final String NEW_PASSWORD = "account-check-new-password";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @LocalServerPort int port;

    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>();
    long owner, staff, colleague, stranger, branch, other;

    @BeforeEach void setup() {
        owner = person("OWNER");
        staff = person("EMPLOYEE");
        colleague = person("EMPLOYEE");
        stranger = person("EMPLOYEE");
        branch = branch("계정 지점");
        other = branch("다른 계정 지점");
        member(staff, branch);
        member(colleague, branch);
        member(stranger, other);
    }

    @AfterEach void clean() {
        String actors = people.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.update("DELETE FROM password_reset_tokens WHERE employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM audit_events WHERE actor_employee_id IN (" + actors + ") OR (entity_type='employee' AND entity_id IN ("
                + actors + "))");
        for (long id : branches) {
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branches WHERE id=?", id);
        }
        jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id IN (" + actors + ")");
        // A photo change records who changed the row; clear it so the rows can go in any order.
        jdbc.update("UPDATE employees SET updated_by=NULL WHERE id IN (" + actors + ")");
        jdbc.update("DELETE FROM employees WHERE id IN (" + actors + ")");
    }

    @Test void aPasswordChangeKeepsThisSessionEndsTheOthersAndIsAudited() throws Exception {
        try (var here = login(staff, PASSWORD); var there = login(staff, PASSWORD)) {
            var wrong = send(here, "POST", "/api/me/password", Map.of("currentPassword", "not-it-at-all", "newPassword", NEW_PASSWORD));
            assertThat(wrong.statusCode()).as(wrong.body()).isEqualTo(422);
            assertThat(code(wrong)).isEqualTo("CURRENT_PASSWORD_MISMATCH");

            var changed = send(here, "POST", "/api/me/password", Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD));
            assertThat(changed.statusCode()).as(changed.body()).isEqualTo(204);
            assertThat(send(here, "GET", "/api/me", null).statusCode()).isEqualTo(200);
            assertThat(send(there, "GET", "/api/me", null).statusCode()).isEqualTo(401);
        }
        assertThat(loginStatus(staff, PASSWORD)).isEqualTo(401);
        assertThat(loginStatus(staff, NEW_PASSWORD)).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT result FROM audit_events WHERE entity_type='employee' AND entity_id=? "
                + "AND action='PASSWORD_CHANGED' ORDER BY id", String.class, staff)).containsExactly("FAILED", "SUCCESS");
        // No password, hash or token is written to the audit rows.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE entity_type='employee' AND entity_id=? AND "
                + "(COALESCE(after_json,'') LIKE ? OR COALESCE(before_json,'') LIKE ?)", Long.class, staff, "%" + NEW_PASSWORD + "%",
                "%" + NEW_PASSWORD + "%")).isZero();
    }

    @Test void aResetIsRefusedWhileResetMailIsOffAndChangesNothing() throws Exception {
        try (var s = login(owner, PASSWORD)) {
            var refused = send(s, "POST", "/api/employees/" + staff + "/password-reset", Map.of());
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(503);
            assertThat(code(refused)).isEqualTo("PASSWORD_RESET_MAIL_DISABLED");
        }
        assertThat(loginStatus(staff, PASSWORD)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE employee_id=?", Long.class, staff)).isZero();
        // Only a manager of the target's branch or an owner may ask; a colleague is refused before anything else.
        try (var s = login(colleague, PASSWORD)) {
            assertThat(send(s, "POST", "/api/employees/" + staff + "/password-reset", Map.of()).statusCode()).isIn(403, 404);
        }
    }

    @Test void aProfilePhotoIsSeenOnlyByColleaguesOfAnActiveBranch() throws Exception {
        try (var s = login(staff, PASSWORD)) {
            var refused = upload(s, "note.png", "not an image".getBytes(StandardCharsets.UTF_8));
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
            assertThat(code(refused)).isEqualTo("AVATAR_TYPE_UNSUPPORTED");
            var uploaded = upload(s, "me.png", PNG);
            assertThat(uploaded.statusCode()).as(uploaded.body()).isEqualTo(200);
            assertThat(json.readTree(uploaded.body()).toString()).contains("/api/employees/" + staff + "/avatar?v=");
            assertThat(sendBytes(s, "/api/employees/" + staff + "/avatar").body()).isEqualTo(PNG);
        }
        try (var s = login(colleague, PASSWORD)) {
            var seen = sendBytes(s, "/api/employees/" + staff + "/avatar");
            assertThat(seen.statusCode()).isEqualTo(200);
            assertThat(seen.headers().firstValue("Content-Type").orElse("")).isEqualTo("image/png");
        }
        try (var s = login(stranger, PASSWORD)) {
            assertThat(sendBytes(s, "/api/employees/" + staff + "/avatar").statusCode()).isEqualTo(404);
        }
        try (var s = login(owner, PASSWORD)) {
            assertThat(sendBytes(s, "/api/employees/" + staff + "/avatar").statusCode()).isEqualTo(200);
        }
    }

    @Test void theAuditSearchIsTheOwnersAndKeepsItsPeriodRule() throws Exception {
        try (var s = login(staff, PASSWORD)) {
            send(s, "POST", "/api/me/password", Map.of("currentPassword", "not-it-at-all", "newPassword", NEW_PASSWORD));
            assertThat(send(s, "GET", "/api/audit-events", null).statusCode()).isEqualTo(403);
        }
        try (var s = login(owner, PASSWORD)) {
            JsonNode page = ok(s, "/api/audit-events?entityType=employee&entityId=" + staff + "&action=PASSWORD_CHANGED");
            assertThat(page.get("items")).hasSize(1);
            JsonNode item = page.get("items").get(0);
            assertThat(item.get("result").asString()).isEqualTo("FAILED");
            // Without from and to the server fills the default period (the last 7 days).
            assertThat(page.at("/period/from").asString()).isNotEmpty();
            assertThat(ok(s, "/api/audit-events/" + item.get("id").asString()).get("action").asString()).isEqualTo("PASSWORD_CHANGED");
            LocalDate to = LocalDate.now();
            var tooLong = send(s, "GET", "/api/audit-events?from=" + to.minusDays(92) + "&to=" + to, null);
            assertThat(tooLong.statusCode()).as(tooLong.body()).isEqualTo(422);
            assertThat(code(tooLong)).isEqualTo("PERIOD_TOO_LONG");
            assertThat(code(send(s, "GET", "/api/audit-events?unknown=1", null))).isEqualTo("VALIDATION_FAILED");
        }
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------

    long branch(String name) {
        var hours = IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        long id = Long.parseLong(branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"), "account-check").id());
        branches.add(id);
        return id;
    }

    long person(String role) {
        String email = UUID.randomUUID() + "@account-check.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,'계정 직원','계정',?,'ACTIVE')",
                email, passwords.encode(PASSWORD), role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    void member(long person, long branch) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,'STAFF','ACTIVE')", person, branch);
    }

    // ---- HTTP -----------------------------------------------------------------------------------------------------

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    int loginStatus(long person, String password) throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            String csrf = json.readTree(raw(client, "GET", "/api/auth/csrf", null, null).body()).get("token").asString();
            String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
            return raw(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", password)).statusCode();
        }
    }

    Session login(long person, String password) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(raw(client, "GET", "/api/auth/csrf", null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(raw(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", password)).statusCode())
                .isEqualTo(200);
        return new Session(client, json.readTree(raw(client, "GET", "/api/auth/csrf", null, null).body()).get("token").asString());
    }

    HttpResponse<String> raw(HttpClient client, String method, String path, String csrf, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (csrf != null) request.header("X-CSRF-Token", csrf);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    HttpResponse<String> send(Session s, String method, String path, Object body) throws Exception {
        return raw(s.client(), method, path, "GET".equals(method) ? null : s.token(), body);
    }

    HttpResponse<byte[]> sendBytes(Session s, String path) throws Exception {
        return s.client().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    HttpResponse<String> upload(Session s, String filename, byte[] content) throws Exception {
        String boundary = "----account-check-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return s.client().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me/avatar"))
                        .header("X-CSRF-Token", s.token()).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    JsonNode ok(Session s, String path) throws Exception {
        var response = send(s, "GET", path, null);
        assertThat(response.statusCode()).as("%s %s", path, response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    String code(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body()).path("code").asString("");
    }
}
