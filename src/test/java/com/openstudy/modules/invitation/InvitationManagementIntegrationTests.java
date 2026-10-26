package com.openstudy.modules.invitation;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.modules.invitation.service.InvitationManagementService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.*;

@MySqlIntegrationTest
class InvitationManagementIntegrationTests {
    private static final String PASSWORD = "invitation-integration-password";
    private static final String API = "/api/invitations";
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired InvitationManagementService invitations;
    @Autowired PlatformTransactionManager transactionManager;
    @LocalServerPort int port;
    private final List<Long> people = new ArrayList<>();
    private final List<Long> branches = new ArrayList<>();
    private final List<Long> presets = new ArrayList<>();
    private long owner, manager, staff, first, second;
    private String hash;

    @BeforeEach void fixture() {
        hash = encoder.encode(PASSWORD);
        owner = person("OWNER", "ACTIVE");
        manager = person("EMPLOYEE", "ACTIVE");
        staff = person("EMPLOYEE", "ACTIVE");
        first = branch();
        second = branch();
        membership(manager, first, "MANAGER", "ACTIVE");
        membership(staff, first, "STAFF", "ACTIVE");
        // A grant alone must never allow STAFF to perform administrator commands.
        for (long id : List.of(manager, staff)) {
            jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) "
                    + "SELECT m.id,p.id FROM branch_memberships m JOIN permission_definitions p ON p.code='people:manage' "
                    + "WHERE m.employee_id=? AND m.branch_id=?", id, first);
        }
    }

    @AfterEach void cleanup() {
        for (long branch : branches) {
            jdbc.update("DELETE FROM invitation_permission_grants WHERE invitation_id IN (SELECT id FROM employee_invitations WHERE branch_id=?)", branch);
            jdbc.update("DELETE FROM invitation_send_jobs WHERE invitation_id IN (SELECT id FROM employee_invitations WHERE branch_id=?)", branch);
            jdbc.update("DELETE FROM employee_invitations WHERE branch_id=?", branch);
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", branch);
            jdbc.update("DELETE FROM workspace_event_outbox WHERE branch_id=?", branch);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", branch);
        }
        for (long id : people) {
            jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", Long.toString(id));
            jdbc.update("DELETE FROM audit_events WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM workspace_event_outbox WHERE target_employee_id=?", id);
            jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id=?", id);
        }
        for (long id : presets) {
            jdbc.update("DELETE FROM workspace_event_outbox WHERE entity_type='permission-presets' AND entity_id=?", id);
            jdbc.update("DELETE FROM permission_presets WHERE id=?", id);
        }
        for (long id : people) jdbc.update("UPDATE employees SET created_by=NULL,updated_by=NULL WHERE id=?", id);
        for (long id : people) jdbc.update("DELETE FROM employees WHERE id=?", id);
        for (long id : branches) jdbc.update("DELETE FROM branches WHERE id=?", id);
        people.clear();
        branches.clear();
        presets.clear();
    }

    private long person(String role, String status) {
        String email = email();
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES (?,?,'초대 검증','초대 검증',?,?)", email, hash, role, status);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    private long branch() {
        String code = UUID.randomUUID().toString().substring(0, 20);
        jdbc.update("INSERT INTO branches(code,name,address) VALUES (?,'초대 검증 지점','서울')", code);
        long id = jdbc.queryForObject("SELECT id FROM branches WHERE code=?", Long.class, code);
        branches.add(id);
        return id;
    }

    private void membership(long person, long branch, String role, String status) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES (?,?,?,?)", person, branch, role, status);
    }

    private static String email() { return UUID.randomUUID() + "@invitation.test"; }
    private String employeeEmail(long id) { return jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, id); }
    private long count(String sql, Object... arguments) { return jdbc.queryForObject(sql, Long.class, arguments); }
    private long dbVersion(long id) { return count("SELECT version FROM employee_invitations WHERE id=?", id); }
    private Map<String, Object> body(long branch, String email, String role, List<String> permissions) {
        return new HashMap<>(Map.of("branchId", Long.toString(branch), "name", "초대받는 직원", "email", email, "role", role, "permissions", permissions));
    }

    private final class Session implements AutoCloseable {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf;
        Session() throws Exception { refreshCsrf(); }
        Session(long employee) throws Exception {
            this();
            assertThat(call("POST", "/api/auth/login", Map.of("email", employeeEmail(employee), "password", PASSWORD)).statusCode()).isEqualTo(200);
            refreshCsrf();
        }
        void refreshCsrf() throws Exception { csrf = json.readTree(call("GET", "/api/auth/csrf", null).body()).get("token").asString(); }
        HttpResponse<String> call(String method, String path, Object body) throws Exception { return call(method, path, body, null); }
        HttpResponse<String> call(String method, String path, Object body, String key) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(java.time.Duration.ofSeconds(20));
            if (csrf != null) request.header("X-CSRF-Token", csrf);
            if (key != null) request.header("Idempotency-Key", key);
            if (body != null) request.header("Content-Type", "application/json");
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> create(Map<String, Object> body) throws Exception { return call("POST", API, body, UUID.randomUUID().toString()); }
        public void close() { client.close(); }
    }

    private JsonNode created(Session session, Map<String, Object> body) throws Exception {
        var response = session.create(body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json.readTree(response.body());
    }
    private void status(HttpResponse<String> response, int expected) { assertThat(response.statusCode()).as(response.body()).isEqualTo(expected); }
    private HttpResponse<String> command(Session s, long id, String operation, long version) throws Exception {
        return s.call("POST", API + "/" + id + "/" + operation, Map.of("version", version));
    }
    private void acceptFixture(long invitation, long employee) {
        jdbc.update("UPDATE employee_invitations SET status='ACCEPTED',accepted_employee_id=?,accepted_at=UTC_TIMESTAMP(6),token_hash=NULL,version=version+1 WHERE id=?", employee, invitation);
    }

    @Test void creationPersistsSnapshotAndSeparateDeliveryWithoutAnyTokenMaterial() throws Exception {
        try (var s = new Session(owner)) {
            String email = email().toUpperCase(java.util.Locale.ROOT);
            var response = s.create(body(first, email, "staff", List.of("operations:write", "members:write")));
            status(response, 201);
            var made = json.readTree(response.body());
            long id = made.get("id").asLong();
            assertThat(made.get("email").asString()).isEqualTo(email.toLowerCase(java.util.Locale.ROOT));
            assertThat(made.get("version").asLong()).isEqualTo(1);
            assertThat(made.get("status").asString()).isEqualTo("초대 대기");
            assertThat(made.get("delivery").asString()).isEqualTo("발송 대기");
            assertThat(response.body()).doesNotContain("token", "hash", "revision", "password", "http://", "https://");
            assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
            assertThat(count("SELECT COUNT(*) FROM invitation_permission_grants WHERE invitation_id=?", id)).isEqualTo(2);
            assertThat(count("SELECT revision FROM employee_invitations WHERE id=?", id)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM employee_invitations WHERE id=? AND token_hash IS NULL", id)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND revision=1 AND status='PENDING'", id)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE request_id=?", response.headers().firstValue("X-Request-ID").orElseThrow())).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=?", first)).isPositive();
            var detail = s.call("GET", API + "/" + id, null);
            status(detail, 200);
            assertThat(json.readTree(detail.body())).isEqualTo(made);
            String audit = jdbc.queryForObject("SELECT after_json FROM audit_events WHERE request_id=?", String.class, response.headers().firstValue("X-Request-ID").orElseThrow());
            assertThat(audit).doesNotContain("token", "hash", "revision", "password");
        }
    }

    @Test void scopedListAndDetailsHideOtherBranchesAndStaffGrantCannotManagePeople() throws Exception {
        try (var o = new Session(owner); var m = new Session(manager); var s = new Session(staff)) {
            var visible = created(o, body(first, email(), "staff", List.of()));
            var hidden = created(o, body(second, email(), "staff", List.of()));
            var list = m.call("GET", API + "?scope=all", null);
            status(list, 200);
            assertThat(json.readTree(list.body()).at("/meta/total").asLong()).isEqualTo(1);
            assertThat(json.readTree(list.body()).at("/items/0/id").asString()).isEqualTo(visible.get("id").asString());
            assertThat(list.body()).doesNotContain(hidden.get("email").asString());
            status(m.call("GET", API + "?scope=" + second, null), 403);
            assertThat(m.call("GET", API + "/" + hidden.get("id").asString(), null).statusCode()).isIn(403, 404);
            status(command(m, hidden.get("id").asLong(), "cancel", 1), 404);
            status(command(m, Long.MAX_VALUE, "cancel", 1), 404);
            assertThat(m.call("GET", API + "/" + visible.get("id").asString() + "?scope=" + second, null).statusCode()).isIn(403, 404);
            status(s.call("GET", API, null), 403);
            status(s.create(body(first, email(), "staff", List.of())), 403);
            var empty = m.call("GET", API + "?q=does-not-exist", null);
            status(empty, 200);
            assertThat(json.readTree(empty.body()).at("/meta/total").asLong()).isZero();
            status(o.call("GET", API + "?sort=password", null), 422);
            status(o.call("GET", API + "?page=0", null), 422);
        }
    }

    @Test void selectedPresetIsMetadataAndLaterPresetChangesDoNotRewritePlannedGrants() throws Exception {
        try (var o = new Session(owner)) {
            var presetResponse = o.call("POST", "/api/permission-presets", Map.of("name", "초대 프리셋-" + UUID.randomUUID(),
                    "role", "staff", "permissions", List.of("members:write")), UUID.randomUUID().toString());
            status(presetResponse, 201);
            var preset = json.readTree(presetResponse.body());
            long presetId = preset.get("id").asLong();
            presets.add(presetId);
            var input = body(first, email(), "staff", List.of("members:write"));
            input.put("presetId", Long.toString(presetId));
            long id = created(o, input).get("id").asLong();
            var changed = o.call("PATCH", "/api/permission-presets/" + presetId,
                    Map.of("version", 1, "name", preset.get("name").asString(), "permissions", List.of()));
            status(changed, 200);
            assertThat(count("SELECT COUNT(*) FROM invitation_permission_grants WHERE invitation_id=?", id)).isEqualTo(1);
            var detail = o.call("GET", API + "/" + id, null);
            status(detail, 200);
            assertThat(json.readTree(detail.body()).get("permissions").get(0).asString()).isEqualTo("members:write");
            assertThat(count("SELECT preset_id FROM employee_invitations WHERE id=?", id)).isEqualTo(presetId);
        }
    }

    @Test void rolesTargetsAndBodyScopeAreCheckedAgainstCurrentAuthority() throws Exception {
        try (var o = new Session(owner); var m = new Session(manager)) {
            status(m.create(body(first, email(), "admin", List.of())), 403);
            status(m.create(body(first, employeeEmail(owner), "staff", List.of())), 403);
            status(m.create(body(second, email(), "staff", List.of())), 403);
            assertThat(o.call("POST", API + "?scope=" + second, body(first, email(), "staff", List.of()), UUID.randomUUID().toString()).statusCode()).isIn(403, 404);
            var admin = created(o, body(first, email(), "admin", List.of()));
            status(command(m, admin.get("id").asLong(), "reissue", 1), 403);
            status(command(m, admin.get("id").asLong(), "cancel", 1), 403);
            var made = created(m, body(first, email(), "staff", List.of()));
            long id = made.get("id").asLong();
            jdbc.update("UPDATE branch_memberships SET role='STAFF',version=version+1 WHERE employee_id=? AND branch_id=?", manager, first);
            status(command(m, id, "cancel", 1), 403);
            assertThat(dbVersion(id)).isZero();
        }
    }

    @Test void idempotentCreateReplaysOnlyForSameInputAndCurrentAuthority() throws Exception {
        try (var m = new Session(manager)) {
            String key = UUID.randomUUID().toString();
            var body = body(first, email(), "staff", List.of());
            var one = m.call("POST", API, body, key);
            status(one, 201);
            var two = m.call("POST", API, new java.util.TreeMap<>(body), key);
            status(two, 201);
            assertThat(two.body()).isEqualTo(one.body());
            assertThat(two.headers().firstValue("X-Request-ID")).isNotEqualTo(one.headers().firstValue("X-Request-ID"));
            body.put("name", "다른 입력");
            status(m.call("POST", API, body, key), 409);
            body.put("name", "초대받는 직원");
            jdbc.update("DELETE g FROM membership_permission_grants g JOIN branch_memberships m ON m.id=g.membership_id WHERE m.employee_id=?", manager);
            status(m.call("POST", API, body, key), 403);
            assertThat(count("SELECT COUNT(*) FROM employee_invitations WHERE created_by=?", manager)).isEqualTo(1);
        }
    }

    @Test void duplicatesAreBlockedButExpiredPendingSlotIsReleasedInline() throws Exception {
        try (var o = new Session(owner)) {
            status(o.create(body(first, employeeEmail(staff), "staff", List.of())), 409);
            String email = email();
            var body = body(first, email, "staff", List.of());
            long old = created(o, body).get("id").asLong();
            status(o.create(body), 409);
            jdbc.update("UPDATE employee_invitations SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND),token_hash=UNHEX(SHA2('obsolete-test-token',256)) WHERE id=?", old);
            long replacement = created(o, body).get("id").asLong();
            assertThat(replacement).isNotEqualTo(old);
            assertThat(jdbc.queryForObject("SELECT status FROM employee_invitations WHERE id=?", String.class, old)).isEqualTo("EXPIRED");
            assertThat(count("SELECT COUNT(*) FROM employee_invitations WHERE id=? AND token_hash IS NULL", old)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND status IN ('PENDING','PROCESSING')", old)).isZero();
            status(command(o, old, "reissue", dbVersion(old) + 1), 409);
        }
    }

    @Test void reissueRotatesRevisionResetsTtlAndInvalidatesOldJobsAndStaleWrites() throws Exception {
        try (var o = new Session(owner)) {
            long id = created(o, body(first, email(), "staff", List.of())).get("id").asLong();
            status(o.call("POST", API + "/" + id + "/reject", Map.of("version", 1, "reason", "아직 수락하지 않음")), 409);
            jdbc.update("UPDATE employee_invitations SET expires_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),token_hash=UNHEX(SHA2('old-test-token',256)) WHERE id=?", id);
            jdbc.update("UPDATE invitation_send_jobs SET status='PROCESSING',worker_id='old-test-worker',locked_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 5 MINUTE) WHERE invitation_id=?", id);
            Instant before = Instant.now();
            var response = command(o, id, "reissue", 1);
            status(response, 200);
            var changed = json.readTree(response.body());
            assertThat(changed.get("version").asLong()).isEqualTo(2);
            Instant expiry = Instant.parse(changed.get("expiresAt").asString());
            assertThat(expiry).isBetween(before.plusSeconds(86390), Instant.now().plusSeconds(86410));
            assertThat(count("SELECT revision FROM employee_invitations WHERE id=?", id)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM employee_invitations WHERE id=? AND token_hash IS NULL", id)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND revision=1 AND status='SKIPPED'", id)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND revision=2 AND status='PENDING'", id)).isEqualTo(1);
            var stale = command(o, id, "cancel", 1);
            status(stale, 409);
            assertThat(stale.body()).contains("latestVersion", "reloadUrl", "requestId");
            assertThat(dbVersion(id)).isEqualTo(1);
            status(command(o, id, "cancel", 2), 200);
            var cancelled = o.call("GET", API + "/" + id, null);
            var replay = command(o, id, "cancel", 1);
            status(replay, 200);
            assertThat(json.readTree(replay.body())).isEqualTo(json.readTree(cancelled.body()));
            assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND status IN ('PENDING','PROCESSING')", id)).isZero();
            status(command(o, id, "reissue", dbVersion(id) + 1), 409);
        }
    }

    @Test void expiredInvitationCanBeReissuedWithNewLifetimeAndNoLiveDuplicate() throws Exception {
        try (var o = new Session(owner)) {
            long id = created(o, body(first, email(), "staff", List.of())).get("id").asLong();
            jdbc.update("UPDATE employee_invitations SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 DAY),token_hash=UNHEX(SHA2('expired-test-token',256)) WHERE id=?", id);
            var expiredDetail = o.call("GET", API + "/" + id, null);
            status(expiredDetail, 200);
            assertThat(json.readTree(expiredDetail.body()).get("status").asString()).isEqualTo("만료");
            String expiredFilter = java.net.URLEncoder.encode("만료", java.nio.charset.StandardCharsets.UTF_8);
            var expiredList = o.call("GET", API + "?scope=" + first + "&status=" + expiredFilter, null);
            status(expiredList, 200);
            assertThat(json.readTree(expiredList.body()).at("/meta/total").asLong()).isEqualTo(1);
            assertThat(json.readTree(expiredList.body()).at("/items/0/id").asLong()).isEqualTo(id);
            // Read projection recognizes expiry without relying on a scheduler or mutating the row.
            assertThat(jdbc.queryForObject("SELECT status FROM employee_invitations WHERE id=?", String.class, id)).isEqualTo("PENDING");
            assertThat(count("SELECT COUNT(*) FROM employee_invitations WHERE id=? AND token_hash IS NOT NULL", id)).isEqualTo(1);
            // The persisted EXPIRED state must satisfy the V3 constraint: no usable token hash.
            jdbc.update("UPDATE employee_invitations SET status='EXPIRED',token_hash=NULL WHERE id=?", id);
            Instant before = Instant.now();
            var response = command(o, id, "reissue", 1);
            status(response, 200);
            var renewed = json.readTree(response.body());
            assertThat(renewed.get("status").asString()).isEqualTo("초대 대기");
            assertThat(renewed.get("version").asLong()).isEqualTo(2);
            assertThat(Instant.parse(renewed.get("expiresAt").asString())).isBetween(before.plusSeconds(86390), Instant.now().plusSeconds(86410));
            assertThat(count("SELECT revision FROM employee_invitations WHERE id=?", id)).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM employee_invitations WHERE id=? AND status='PENDING' AND token_hash IS NULL", id)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND revision=1 AND status='SKIPPED'", id)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND revision=2 AND status='PENDING'", id)).isEqualTo(1);
            // Explicit PENDING reissue rotates again instead of creating a second invitation.
            status(command(o, id, "reissue", 2), 200);
            assertThat(count("SELECT revision FROM employee_invitations WHERE id=?", id)).isEqualTo(3);
            assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND status IN ('PENDING','PROCESSING')", id)).isEqualTo(1);
        }
    }

    @Test void rejectedAcceptedInvitationRevokesOnlyPendingMembershipAndKeepsHistory() throws Exception {
        long target = person("EMPLOYEE", "ACTIVE");
        membership(target, first, "STAFF", "PENDING");
        membership(target, second, "STAFF", "ACTIVE");
        try (var o = new Session(owner)) {
            long id = created(o, body(first, employeeEmail(target), "staff", List.of("members:write"))).get("id").asLong();
            acceptFixture(id, target);
            jdbc.update("UPDATE employee_invitations SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 2 DAY) WHERE id=?", id);
            var waiting = o.call("GET", API + "/" + id, null);
            status(waiting, 200);
            assertThat(json.readTree(waiting.body()).get("status").asString()).isEqualTo("승인 대기");
            String waitingFilter = java.net.URLEncoder.encode("승인 대기", java.nio.charset.StandardCharsets.UTF_8);
            var waitingList = o.call("GET", API + "?scope=" + first + "&status=" + waitingFilter, null);
            status(waitingList, 200);
            assertThat(json.readTree(waitingList.body()).at("/meta/total").asLong()).isEqualTo(1);
            status(command(o, id, "reissue", 2), 409);
            status(command(o, id, "cancel", 2), 409);
            status(o.call("POST", API + "/" + id + "/reject", Map.of("version", 2, "reason", "  ")), 422);
            var response = o.call("POST", API + "/" + id + "/reject", Map.of("version", 2, "reason", "확인이 필요합니다"));
            status(response, 200);
            var rejected = json.readTree(response.body());
            assertThat(rejected.get("status").asString()).isEqualTo("거절");
            assertThat(rejected.get("rejectionReason").asString()).isEqualTo("확인이 필요합니다");
            assertThat(jdbc.queryForObject("SELECT status FROM branch_memberships WHERE employee_id=? AND branch_id=?", String.class, target, first)).isEqualTo("REVOKED");
            assertThat(jdbc.queryForObject("SELECT status FROM branch_memberships WHERE employee_id=? AND branch_id=?", String.class, target, second)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT status FROM employees WHERE id=?", String.class, target)).isEqualTo("ACTIVE");
            assertThat(count("SELECT COUNT(*) FROM employee_invitations WHERE id=? AND accepted_at IS NOT NULL", id)).isEqualTo(1);
            var repeated = o.call("POST", API + "/" + id + "/reject", Map.of("version", 1, "reason", "다른 사유"));
            status(repeated, 200);
            assertThat(json.readTree(repeated.body())).isEqualTo(rejected);
        }
    }

    @Test void rejectionFailsClosedWhenPendingMembershipRoleNoLongerMatchesInvitation() throws Exception {
        long target = person("EMPLOYEE", "ACTIVE");
        membership(target, first, "MANAGER", "PENDING");
        try (var o = new Session(owner)) {
            long id = created(o, body(first, employeeEmail(target), "staff", List.of())).get("id").asLong();
            acceptFixture(id, target);
            long auditCount = count("SELECT COUNT(*) FROM audit_events WHERE branch_id=?", first);
            status(o.call("POST", API + "/" + id + "/reject", Map.of("version", 2, "reason", "역할 불일치")), 409);
            assertThat(jdbc.queryForObject("SELECT status FROM branch_memberships WHERE employee_id=? AND branch_id=?", String.class, target, first)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT role FROM branch_memberships WHERE employee_id=? AND branch_id=?", String.class, target, first)).isEqualTo("MANAGER");
            assertThat(jdbc.queryForObject("SELECT status FROM employee_invitations WHERE id=?", String.class, id)).isEqualTo("ACCEPTED");
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE branch_id=?", first)).isEqualTo(auditCount);
        }
    }

    @Test void terminalReplayRechecksCurrentTargetRoleAndRejectNeverRevokesActiveMembership() throws Exception {
        long target = person("EMPLOYEE", "ACTIVE");
        try (var o = new Session(owner); var m = new Session(manager)) {
            long id = created(m, body(first, employeeEmail(target), "staff", List.of())).get("id").asLong();
            status(command(m, id, "cancel", 1), 200);
            membership(target, first, "MANAGER", "ACTIVE");
            status(command(m, id, "cancel", 1), 403);
            long accepted = created(o, body(second, employeeEmail(target), "staff", List.of())).get("id").asLong();
            membership(target, second, "STAFF", "ACTIVE");
            acceptFixture(accepted, target);
            var rejection = o.call("POST", API + "/" + accepted + "/reject", Map.of("version", 2, "reason", "별도 소속 보존"));
            // An existing active membership is never revoked by rejecting an invitation.
            assertThat(rejection.statusCode()).as(rejection.body()).isIn(200, 409);
            assertThat(jdbc.queryForObject("SELECT status FROM branch_memberships WHERE employee_id=? AND branch_id=?", String.class, target, second)).isEqualTo("ACTIVE");
        }
    }

    @Test void csrfRestrictedSessionsUnknownFieldsAndPermissionInputsFailSafely() throws Exception {
        long pending = person("EMPLOYEE", "PENDING");
        membership(pending, first, "STAFF", "PENDING");
        try (var o = new Session(owner); var waiting = new Session(pending); var anonymous = new Session()) {
            var body = body(first, email(), "staff", List.of());
            status(o.call("POST", API, body), 422);
            String csrf = o.csrf;
            o.csrf = "forged";
            status(o.create(body), 403);
            o.csrf = null;
            status(o.create(body), 403);
            o.csrf = csrf;
            status(waiting.call("GET", API, null), 403);
            status(waiting.create(body), 403);
            status(anonymous.call("GET", API, null), 401);
            body.put("token", "must-not-be-stored");
            status(o.create(body), 422);
            body.remove("token");
            body.put("permissions", List.of("not-a-permission"));
            status(o.create(body), 422);
            body.put("permissions", List.of("people:manage"));
            status(o.create(body), 422);
            body.put("permissions", List.of());
            body.put("email", "bad-address");
            status(o.create(body), 422);
            assertThat(count("SELECT COUNT(*) FROM employee_invitations WHERE created_by=?", owner)).isZero();
        }
    }

    @Test void concurrentDifferentKeysCannotCreateTwoActiveInvitationsForOneAddress() throws Exception {
        try (var one = new Session(owner); var two = new Session(owner); var pool = Executors.newFixedThreadPool(2)) {
            var body = body(first, email(), "staff", List.of());
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { assertThat(start.await(10, TimeUnit.SECONDS)).isTrue(); return one.create(body); });
            var b = pool.submit(() -> { assertThat(start.await(10, TimeUnit.SECONDS)).isTrue(); return two.create(body); });
            start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS).statusCode(), b.get(20, TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(201, 409);
            assertThat(count("SELECT COUNT(*) FROM employee_invitations WHERE branch_id=? AND invitee_email=? AND status='PENDING'", first, body.get("email"))).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs j JOIN employee_invitations i ON i.id=j.invitation_id WHERE i.branch_id=?", first)).isEqualTo(1);
        }
    }

    @Test void concurrentSameIdempotencyKeyReplaysOneCreatedAggregate() throws Exception {
        try (var one = new Session(owner); var two = new Session(owner); var pool = Executors.newFixedThreadPool(2)) {
            var body = body(first, email(), "staff", List.of("members:write"));
            String key = UUID.randomUUID().toString();
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { assertThat(start.await(10, TimeUnit.SECONDS)).isTrue(); return one.call("POST", API, body, key); });
            var b = pool.submit(() -> { assertThat(start.await(10, TimeUnit.SECONDS)).isTrue(); return two.call("POST", API, body, key); });
            start.countDown();
            var firstResponse = a.get(20, TimeUnit.SECONDS);
            var secondResponse = b.get(20, TimeUnit.SECONDS);
            status(firstResponse, 201);
            status(secondResponse, 201);
            assertThat(secondResponse.body()).isEqualTo(firstResponse.body());
            assertThat(count("SELECT COUNT(*) FROM employee_invitations WHERE branch_id=? AND invitee_email=?", first, body.get("email"))).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs j JOIN employee_invitations i ON i.id=j.invitation_id WHERE i.branch_id=?", first)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE actor_employee_id=? AND idempotency_key=?", owner, key)).isEqualTo(1);
        }
    }

    @Test void cancellationWaitsForAcceptanceLockAndCannotOverwriteAcceptedState() throws Exception {
        long target = person("EMPLOYEE", "ACTIVE");
        try (var o = new Session(owner); var pool = Executors.newFixedThreadPool(2)) {
            long id = created(o, body(first, employeeEmail(target), "staff", List.of())).get("id").asLong();
            var locked = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var acceptance = pool.submit(() -> new TransactionTemplate(transactionManager).execute(tx -> {
                jdbc.queryForObject("SELECT id FROM employee_invitations WHERE id=? FOR UPDATE", Long.class, id);
                acceptFixture(id, target);
                locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("acceptance release timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return true;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var cancellation = pool.submit(() -> command(o, id, "cancel", 1));
            try {
                assertThatThrownBy(() -> cancellation.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally { release.countDown(); }
            assertThat(acceptance.get(15, TimeUnit.SECONDS)).isTrue();
            status(cancellation.get(15, TimeUnit.SECONDS), 409);
            assertThat(jdbc.queryForObject("SELECT status FROM employee_invitations WHERE id=?", String.class, id)).isEqualTo("ACCEPTED");
            assertThat(count("SELECT COUNT(*) FROM employee_invitations WHERE id=? AND cancelled_at IS NULL", id)).isEqualTo(1);
        }
    }

    @Test void rollbackRemovesInvitationPlannedGrantsJobsAuditOutboxAndIdempotencyTogether() {
        String email = email(), key = UUID.randomUUID().toString(), request = "invitation-rollback-" + UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            invitations.create(owner, "all", body(first, email, "staff", List.of("members:write")), key, request);
            assertThat(count("SELECT COUNT(*) FROM employee_invitations WHERE invitee_email=?", email)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs j JOIN employee_invitations i ON i.id=j.invitation_id WHERE i.invitee_email=?", email)).isEqualTo(1);
            tx.setRollbackOnly();
        });
        assertThat(count("SELECT COUNT(*) FROM employee_invitations WHERE invitee_email=?", email)).isZero();
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs j JOIN employee_invitations i ON i.id=j.invitation_id WHERE i.branch_id=?", first)).isZero();
        assertThat(count("SELECT COUNT(*) FROM invitation_permission_grants g JOIN employee_invitations i ON i.id=g.invitation_id WHERE i.branch_id=?", first)).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE request_id=?", request)).isZero();
        assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=?", first)).isZero();
        assertThat(count("SELECT COUNT(*) FROM idempotency_records WHERE actor_employee_id=? AND idempotency_key=?", owner, key)).isZero();
    }
}
