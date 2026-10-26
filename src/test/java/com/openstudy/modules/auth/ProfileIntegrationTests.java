package com.openstudy.modules.auth;

import java.net.*;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.modules.auth.service.CurrentUserService;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.*;

@MySqlIntegrationTest
class ProfileIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired EmployeeAccountService accounts;
    @Autowired CurrentUserService users;
    @Autowired PlatformTransactionManager transactions;
    @LocalServerPort int port;
    private final List<Long> people = new ArrayList<>();
    private long owner, manager, staff, branch;
    private String hash;
    private static final String PASSWORD = "profile-test-password";
    private static final String PROFILE = "/api/me/profile";

    @BeforeEach void fixture() {
        hash = encoder.encode(PASSWORD);
        owner = person("OWNER", "ACTIVE");
        manager = person("EMPLOYEE", "ACTIVE");
        staff = person("EMPLOYEE", "ACTIVE");
        String code = UUID.randomUUID().toString().substring(0, 20);
        jdbc.update("INSERT INTO branches(code,name,address) VALUES (?,'프로필 검증 지점','서울')", code);
        branch = jdbc.queryForObject("SELECT id FROM branches WHERE code=?", Long.class, code);
        for (long id : List.of(manager, staff)) {
            jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES (?,?,?,'ACTIVE')",
                    id, branch, id == manager ? "MANAGER" : "STAFF");
        }
        jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id "
                + "FROM branch_memberships m JOIN permission_definitions p ON p.code='people:manage' WHERE m.employee_id=?", manager);
    }

    @AfterEach void cleanup() {
        for (long id : people) {
            jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", Long.toString(id));
            jdbc.update("DELETE FROM audit_events WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM workspace_event_outbox WHERE target_employee_id=? OR (entity_type IN ('employee','employees') AND entity_id=?)", id, id);
            jdbc.update("DELETE FROM branch_memberships WHERE employee_id=?", id);
        }
        for (long id : people) jdbc.update("UPDATE employees SET updated_by=NULL,created_by=NULL WHERE id=?", id);
        for (long id : people) jdbc.update("DELETE FROM employees WHERE id=?", id);
        jdbc.update("DELETE FROM branches WHERE id=?", branch);
        people.clear();
    }

    private long person(String role, String status) {
        String email = UUID.randomUUID() + "@profile.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,avatar_url,global_role,status) "
                + "VALUES (?,?,'직원','이전 별명','https://example.test/avatar.png',?,?)", email, hash, role, status);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    private final class Session implements AutoCloseable {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf;
        Session() throws Exception { refreshCsrf(); }
        Session(long id) throws Exception { this(); login(id); }
        void refreshCsrf() throws Exception { csrf = json.readTree(call("GET", "/api/auth/csrf", null).body()).get("token").asString(); }
        HttpResponse<String> login(long id) throws Exception {
            var result = call("POST", "/api/auth/login", Map.of("email", accounts.byId(id).email(), "password", PASSWORD));
            assertThat(result.statusCode()).isEqualTo(200);
            refreshCsrf();
            return result;
        }
        HttpResponse<String> call(String method, String path, Object body) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (csrf != null) request.header("X-CSRF-Token", csrf);
            if (body != null) request.header("Content-Type", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> save(Object body) throws Exception { return call("PATCH", PROFILE, body); }
        public void close() { client.close(); }
    }

    private Map<String, Object> immutableAccount(long id) {
        return jdbc.queryForMap("SELECT email,password_hash,name,avatar_url,global_role,status,theme,created_at,created_by FROM employees WHERE id=?", id);
    }
    private List<Map<String, Object>> memberships(long id) {
        return jdbc.queryForList("SELECT m.*,g.permission_id FROM branch_memberships m LEFT JOIN membership_permission_grants g ON g.membership_id=m.id WHERE m.employee_id=? ORDER BY m.id,g.permission_id", id);
    }
    private void untouched(long id) {
        assertThat(accounts.byId(id).nickname()).isEqualTo("이전 별명");
        assertThat(accounts.byId(id).version()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE actor_employee_id=?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_event_outbox WHERE entity_id=? AND entity_type IN ('employee','employees')", Long.class, id)).isZero();
    }
    private void validation(HttpResponse<String> response, String field) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
        var error = json.readTree(response.body());
        assertThat(error.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.get("fieldErrors").has(field)).isTrue();
        assertThat(error.get("requestId").asString()).isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
    }

    @Test void allThreeRolesSaveReloadReloginAndWriteSafeAtomicTracking() throws Exception {
        for (long id : List.of(owner, manager, staff)) {
            var fixed = immutableAccount(id);
            var grants = memberships(id);
            var before = accounts.byId(id);
            try (var session = new Session(id)) {
                var response = session.save(Map.of("nickname", "  저장한 별명 " + id + "  ", "version", 1));
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
                var saved = json.readTree(response.body());
                String nickname = "저장한 별명 " + id;
                assertThat(saved.at("/user/nickname").asString()).isEqualTo(nickname);
                assertThat(saved.at("/user/id").asString()).isEqualTo(Long.toString(id));
                assertThat(saved.at("/user/version").asLong()).isEqualTo(2);
                assertThat(saved.get("snapshotVersion").asString()).isEqualTo("employee-" + id + "-1");
                assertThat(response.body()).doesNotContain(hash, "password", "token", "SESSION", PASSWORD);
                assertThat(json.readTree(session.call("GET", "/api/me", null).body()).get("user")).isEqualTo(saved.get("user"));
                assertThat(session.call("POST", "/api/auth/logout", null).statusCode()).isEqualTo(200);
                session.refreshCsrf();
                assertThat(json.readTree(session.login(id).body()).at("/user/nickname").asString()).isEqualTo(nickname);
                assertThat(accounts.byId(id).nickname()).isEqualTo(nickname);
                assertThat(accounts.byId(id).updatedAt()).isAfter(before.updatedAt());
                assertThat(immutableAccount(id)).isEqualTo(fixed);
                assertThat(memberships(id)).isEqualTo(grants);
                assertThat(jdbc.queryForObject("SELECT updated_by FROM employees WHERE id=?", Long.class, id)).isEqualTo(id);
                var audit = jdbc.queryForMap("SELECT * FROM audit_events WHERE actor_employee_id=?", id);
                assertThat(audit.get("action")).isEqualTo("UPDATE_PROFILE");
                assertThat(audit.get("request_id")).isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
                assertThat(audit.get("before_json").toString()).contains("이전 별명", "version").doesNotContain(hash, "password", "token");
                assertThat(audit.get("after_json").toString()).contains(nickname).doesNotContain(hash, "password", "token");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspace_event_outbox WHERE entity_id=? AND entity_type IN ('employee','employees') AND event_type='INVALIDATE'", Long.class, id)).isEqualTo(2);
            }
        }
    }

    @Test void rejectsProtectedFieldsAndEveryAttemptToSelectAnotherEmployee() throws Exception {
        var fixed = immutableAccount(staff);
        var grants = memberships(staff);
        try (var session = new Session(staff)) {
            for (String field : List.of("email", "owner", "status", "memberships", "avatar", "avatar_url", "id", "employeeId",
                    "name", "password", "permissions", "branchId", "global_role", "theme", "unknown")) {
                var body = new HashMap<String, Object>(Map.of("nickname", "위조", "version", 1));
                body.put(field, Long.toString(manager));
                validation(session.save(body), field);
            }
            for (String field : List.of("id", "employeeId", "scope")) {
                validation(session.call("PATCH", PROFILE + "?" + field + "=" + manager, Map.of("nickname", "위조", "version", 1)), field);
            }
            assertThat(session.call("PATCH", PROFILE + "/" + manager, Map.of("nickname", "위조", "version", 1)).statusCode()).isEqualTo(404);
            assertThat(session.call("PATCH", "/api/employees/" + manager, Map.of("nickname", "위조", "version", 1)).statusCode()).isEqualTo(403);
        }
        assertThat(immutableAccount(staff)).isEqualTo(fixed);
        assertThat(memberships(staff)).isEqualTo(grants);
        untouched(staff); untouched(manager);
    }

    @Test void invalidNamesVersionsAndBodiesReturnCommonFieldErrorsWithoutWrites() throws Exception {
        try (var session = new Session(staff)) {
            for (Object nickname : Arrays.asList("", " \t\n", "가".repeat(101), null, 7, true, List.of("별명"))) {
                var body = new HashMap<String, Object>(); body.put("nickname", nickname); body.put("version", 1);
                validation(session.save(body), "nickname");
            }
            validation(session.save(Map.of("version", 1)), "nickname");
            for (Object version : Arrays.asList(null, 0, -1, "1", 1.0, true, 9_007_199_254_740_992L)) {
                var body = new HashMap<String, Object>(); body.put("nickname", "별명"); body.put("version", version);
                validation(session.save(body), "version");
            }
            validation(session.save(Map.of("nickname", "별명")), "version");
            validation(session.save(List.of()), "$body");
            assertThat(session.save(Map.of("nickname", "가".repeat(100), "version", 1)).statusCode()).isEqualTo(200);
        }
    }

    @Test void anonymousMissingAndInvalidCsrfCannotChangeProfile() throws Exception {
        try (var anonymous = new Session(); var session = new Session(staff)) {
            assertThat(anonymous.save(Map.of("nickname", "위조", "version", 1)).statusCode()).isEqualTo(401);
            for (String token : Arrays.asList(null, "incorrect")) {
                session.csrf = token;
                var response = session.save(Map.of("nickname", "위조", "version", 1));
                assertThat(response.statusCode()).isEqualTo(403);
                assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("CSRF_INVALID");
            }
        }
        untouched(staff);
    }

    @Test void approvalOnlyStaysRestrictedAfterApprovalAndLimitedAccountsCannotSave() throws Exception {
        long pending = person("EMPLOYEE", "PENDING");
        try (var session = new Session(pending)) {
            assertThat(session.save(Map.of("nickname", "위조", "version", 1)).statusCode()).isEqualTo(403);
            jdbc.update("UPDATE employees SET status='ACTIVE' WHERE id=?", pending);
            assertThat(session.save(Map.of("nickname", "위조", "version", 1)).statusCode()).isEqualTo(403);
            untouched(pending);
        }
        for (String status : List.of("PENDING", "SUSPENDED", "LEFT")) {
            jdbc.update("UPDATE employees SET status='ACTIVE' WHERE id=?", staff);
            try (var session = new Session(staff)) {
                jdbc.update("UPDATE employees SET status=? WHERE id=?", status, staff);
                assertThat(session.save(Map.of("nickname", "위조", "version", 1)).statusCode()).isEqualTo(401);
                // The command repeats the account check under its lock even if a filter ran earlier.
                assertThatThrownBy(() -> accounts.updateProfile(staff, Map.of("nickname", "위조", "version", 1), "restricted"))
                        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            }
            untouched(staff);
        }
    }

    @Test void profileAndAdminShareVersionAndConflictsGiveMeReloadMetadata() throws Exception {
        try (var admin = new Session(owner); var session = new Session(staff)) {
            assertThat(admin.call("PATCH", "/api/employees/" + staff, Map.of("version", 1, "name", "관리자 변경")).statusCode()).isEqualTo(200);
            var conflict = session.save(Map.of("version", 1, "nickname", "미저장"));
            assertThat(conflict.statusCode()).isEqualTo(409);
            var error = json.readTree(conflict.body());
            assertThat(error.get("code").asString()).isEqualTo("VERSION_CONFLICT");
            assertThat(error.get("latestVersion").asLong()).isEqualTo(2);
            assertThat(error.get("reloadUrl").asString()).isEqualTo("/api/me");
            assertThat(accounts.byId(staff).nickname()).isEqualTo("이전 별명");
            assertThat(session.save(Map.of("version", 2, "nickname", "재조회 후 저장")).statusCode()).isEqualTo(200);
            assertThat(admin.call("PATCH", "/api/employees/" + staff, Map.of("version", 2, "name", "오래된 편집")).statusCode()).isEqualTo(409);
            assertThat(accounts.byId(staff).name()).isEqualTo("관리자 변경");
            assertThat(accounts.byId(staff).version()).isEqualTo(2);
        }
    }

    @Test void simultaneousAdminAndProfileHttpSavesHaveOneWinnerAndOneConflict() throws Exception {
        try (var admin = new Session(owner); var session = new Session(staff); var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var one = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return session.save(Map.of("version", 1, "nickname", "경합 별명")); });
            var two = pool.submit(() -> { start.await(10, TimeUnit.SECONDS); return admin.call("PATCH", "/api/employees/" + staff, Map.of("version", 1, "name", "경합 이름")); });
            start.countDown();
            var profile = one.get(15, TimeUnit.SECONDS); var management = two.get(15, TimeUnit.SECONDS);
            assertThat(List.of(profile.statusCode(), management.statusCode())).containsExactlyInAnyOrder(200, 409);
            assertThat(accounts.byId(staff).version()).isEqualTo(1);
            assertThat(accounts.byId(staff).nickname()).isEqualTo(profile.statusCode() == 200 ? "경합 별명" : "이전 별명");
            assertThat(accounts.byId(staff).name()).isEqualTo(management.statusCode() == 200 ? "경합 이름" : "직원");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE entity_type='employee' AND entity_id=?", Long.class, staff)).isEqualTo(1);
        }
    }

    @Test void transactionRollbackRestoresAccountAuditAndOutbox() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            users.updateProfile(staff, "WORKSPACE", Map.of("version", 1, "nickname", "롤백"), "rollback-profile");
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        untouched(staff);
        assertThatThrownBy(() -> users.updateProfile(staff, "APPROVAL_ONLY", Map.of("version", 1, "nickname", "제한"), "restricted"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void staleTransactionSnapshotCannotSaveAfterAdminRestrictsAccount() throws Exception {
        var snapshotRead = new CountDownLatch(1);
        var restricted = new CountDownLatch(1);
        try (var admin = new Session(owner); var pool = Executors.newSingleThreadExecutor()) {
            var profile = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                assertThat(accounts.byId(staff).status()).isEqualTo("ACTIVE");
                snapshotRead.countDown();
                try { assertThat(restricted.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                assertThatThrownBy(() -> users.updateProfile(staff, "WORKSPACE", Map.of("version", 1, "nickname", "오래된 권한"), "stale-state"))
                        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                status.setRollbackOnly();
                return null;
            }));
            try {
                assertThat(snapshotRead.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(admin.call("PATCH", "/api/employees/" + staff, Map.of("version", 1, "status", "이용 제한")).statusCode()).isEqualTo(200);
            } finally { restricted.countDown(); }
            profile.get(15, TimeUnit.SECONDS);
        }
        assertThat(accounts.byId(staff).nickname()).isEqualTo("이전 별명");
        assertThat(accounts.byId(staff).version()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE request_id='stale-state'", Long.class)).isZero();
    }

    @Test void publishedProfileAndCurrentUserContractMatchesActualResponse() throws Exception {
        var spec = json.readTree(Files.readString(Path.of("docs/design/foundation/openapi.json")));
        assertThat(spec.at("/paths/~1me~1profile/patch/requestBody/content/application~1json/schema/$ref").asString())
                .isEqualTo("#/components/schemas/ProfileUpdate");
        var input = spec.at("/components/schemas/ProfileUpdate");
        assertThat(input.get("additionalProperties").asBoolean()).isFalse();
        assertThat(input.get("required").size()).isEqualTo(2);
        assertThat(input.get("properties").size()).isEqualTo(2);
        assertThat(input.at("/properties/nickname/maxLength").asInt()).isEqualTo(100);
        try (var session = new Session(manager)) {
            var response = session.save(Map.of("nickname", "계약 검증", "version", 1));
            assertThat(response.statusCode()).isEqualTo(200);
            var result = json.readTree(response.body());
            contractKeys(spec.at("/components/schemas/CurrentUser"), result);
            contractKeys(spec.at("/components/schemas/SessionUser"), result.get("user"));
            contractKeys(spec.at("/components/schemas/SessionMembership"), result.at("/user/memberships/0"));
            contractKeys(spec.at("/components/schemas/SessionBranch"), result.at("/branches/0"));
        }
    }

    private void contractKeys(tools.jackson.databind.JsonNode schema, tools.jackson.databind.JsonNode response) {
        assertThat(response.size()).isEqualTo(schema.get("properties").size());
        for (var field : schema.get("required")) assertThat(response.has(field.asString())).as(field.asString()).isTrue();
    }
}
