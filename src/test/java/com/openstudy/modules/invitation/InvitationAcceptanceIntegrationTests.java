package com.openstudy.modules.invitation;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.modules.invitation.service.InvitationAcceptanceService;
import com.openstudy.modules.invitation.service.InvitationManagementService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.*;

@MySqlIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class InvitationAcceptanceIntegrationTests {
    private static final String PASSWORD = "acceptance-integration-password";
    private static final String NEW_PASSWORD = "새 직원 비밀번호 1234";
    private static final String API = "/api/invitations";
    private static final SecureRandom RANDOM = new SecureRandom();
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired InvitationAcceptanceService acceptance;
    @Autowired InvitationManagementService invitations;
    @Autowired PlatformTransactionManager transactionManager;
    @LocalServerPort int port;
    private final List<Long> people = new ArrayList<>();
    private final List<Long> branches = new ArrayList<>();
    private final List<String> emails = new ArrayList<>();
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
        jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) "
                + "SELECT m.id,p.id FROM branch_memberships m JOIN permission_definitions p ON p.code='people:manage' "
                + "WHERE m.employee_id=? AND m.branch_id=?", manager, first);
    }

    @AfterEach void cleanup() {
        // Accounts created by acceptance are not in the fixture list; collect them through their invitations.
        for (String email : emails) {
            jdbc.queryForList("SELECT id FROM employees WHERE email=?", Long.class, email).forEach(id -> {
                if (!people.contains(id)) people.add(id);
            });
        }
        for (long branch : branches) {
            jdbc.update("DELETE FROM invitation_permission_grants WHERE invitation_id IN (SELECT id FROM employee_invitations WHERE branch_id=?)", branch);
            jdbc.update("DELETE FROM invitation_send_jobs WHERE invitation_id IN (SELECT id FROM employee_invitations WHERE branch_id=?)", branch);
            jdbc.update("DELETE FROM employee_invitations WHERE branch_id=?", branch);
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", branch);
            jdbc.update("DELETE FROM workspace_event_outbox WHERE branch_id=?", branch);
            jdbc.update("DELETE FROM membership_permission_grants WHERE membership_id IN (SELECT id FROM branch_memberships WHERE branch_id=?)", branch);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", branch);
        }
        for (long id : people) {
            jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", Long.toString(id));
            jdbc.update("DELETE FROM audit_events WHERE actor_employee_id=?", id);
            jdbc.update("DELETE FROM workspace_event_outbox WHERE target_employee_id=?", id);
            jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id=?", id);
        }
        for (long id : people) jdbc.update("UPDATE employees SET created_by=NULL,updated_by=NULL WHERE id=?", id);
        for (long id : people) jdbc.update("DELETE FROM employees WHERE id=?", id);
        for (long id : branches) jdbc.update("DELETE FROM branches WHERE id=?", id);
        people.clear();
        branches.clear();
        emails.clear();
    }

    private long person(String role, String status) {
        String email = email();
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES (?,?,'수락 검증','수락 검증',?,?)", email, hash, role, status);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    private long branch() {
        String code = UUID.randomUUID().toString().substring(0, 20);
        jdbc.update("INSERT INTO branches(code,name,address) VALUES (?,'수락 검증 지점','서울')", code);
        long id = jdbc.queryForObject("SELECT id FROM branches WHERE code=?", Long.class, code);
        branches.add(id);
        return id;
    }

    private void membership(long person, long branch, String role, String status) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES (?,?,?,?)", person, branch, role, status);
    }

    private String email() {
        String email = UUID.randomUUID() + "@acceptance.test";
        emails.add(email);
        return email;
    }
    private String employeeEmail(long id) { return jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, id); }
    private long count(String sql, Object... arguments) { return jdbc.queryForObject(sql, Long.class, arguments); }
    private String text(String sql, Object... arguments) { return jdbc.queryForObject(sql, String.class, arguments); }
    private long dbVersion(long id) { return count("SELECT version FROM employee_invitations WHERE id=?", id); }
    private void status(HttpResponse<String> response, int expected) { assertThat(response.statusCode()).as(response.body()).isEqualTo(expected); }
    private JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }

    /** Creates an invitation through the management API, then attaches a link as the delivery worker would. */
    private Invitation invite(long actor, long branch, String email, String role, List<String> permissions) throws Exception {
        try (var session = new Session(actor)) {
            var response = session.call("POST", API, Map.of("branchId", Long.toString(branch), "name", "초대받는 직원",
                    "email", email, "role", role, "permissions", permissions), UUID.randomUUID().toString());
            status(response, 201);
            long id = body(response).get("id").asLong();
            byte[] entropy = new byte[32];
            RANDOM.nextBytes(entropy);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
            jdbc.update("UPDATE employee_invitations SET token_hash=?,version=version+1 WHERE id=?", sha256(token), id);
            jdbc.update("UPDATE invitation_send_jobs SET status='SENT',sent_at=UTC_TIMESTAMP(6) WHERE invitation_id=?", id);
            return new Invitation(id, email, token);
        }
    }

    private record Invitation(long id, String email, String token) {}

    private static byte[] sha256(String token) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
    }

    private static Map<String, Object> newAccount(Invitation invitation, String password) {
        return Map.of("mode", "NEW_ACCOUNT", "token", invitation.token(), "email", invitation.email(), "password", password);
    }

    private static Map<String, Object> existingAccount(Invitation invitation) {
        return Map.of("mode", "EXISTING_ACCOUNT", "token", invitation.token(), "email", invitation.email());
    }

    private HttpResponse<String> approve(Session session, long id) throws Exception {
        return session.call("POST", API + "/" + id + "/approve", Map.of("version", dbVersion(id) + 1));
    }

    private final class Session implements AutoCloseable {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf;
        Session() throws Exception { refreshCsrf(); }
        Session(long employee) throws Exception { this(employeeEmail(employee), PASSWORD); }
        Session(String email, String password) throws Exception {
            this();
            status(call("POST", "/api/auth/login", Map.of("email", email, "password", password)), 200);
            refreshCsrf();
        }
        void refreshCsrf() throws Exception { csrf = json.readTree(call("GET", "/api/auth/csrf", null).body()).get("token").asString(); }
        HttpResponse<String> call(String method, String path, Object body) throws Exception { return call(method, path, body, null); }
        HttpResponse<String> call(String method, String path, Object body, String key) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(java.time.Duration.ofSeconds(30));
            if (csrf != null) request.header("X-CSRF-Token", csrf);
            if (key != null) request.header("Idempotency-Key", key);
            if (body != null) request.header("Content-Type", "application/json");
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        public void close() { client.close(); }
    }

    @Test void newEmployeeAcceptsWaitsInRestrictedSessionAndEntersWorkAfterApprovalAndRelogin(CapturedOutput output) throws Exception {
        var invitation = invite(manager, first, email(), "staff", List.of("members:write"));
        try (var anonymous = new Session()) {
            var preview = anonymous.call("POST", API + "/preview", Map.of("token", invitation.token()));
            status(preview, 200);
            assertThat(body(preview).get("email").asString()).isEqualTo(invitation.email());
            assertThat(body(preview).get("name").asString()).isEqualTo("초대받는 직원");
            assertThat(body(preview).at("/branch/id").asString()).isEqualTo(Long.toString(first));
            assertThat(body(preview).get("role").asString()).isEqualTo("staff");
            assertThat(body(preview).get("acceptanceMode").asString()).isEqualTo("NEW_ACCOUNT");
            assertThat(preview.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
            assertThat(preview.headers().firstValue("Referrer-Policy")).contains("no-referrer");
            assertThat(preview.body()).doesNotContain(invitation.token(), "token", "hash", "revision", "permissions");

            var accepted = anonymous.call("POST", API + "/accept", newAccount(invitation, NEW_PASSWORD));
            status(accepted, 200);
            assertThat(body(accepted).get("invitationId").asString()).isEqualTo(Long.toString(invitation.id()));
            assertThat(body(accepted).get("status").asString()).isEqualTo("승인 대기");
            assertThat(body(accepted).get("nextAction").asString()).isEqualTo("LOGIN_FOR_APPROVAL_STATUS");
            // No session is issued by acceptance.
            status(anonymous.call("GET", "/api/me", null), 401);
            // The link is single-use.
            status(anonymous.call("POST", API + "/preview", Map.of("token", invitation.token())), 422);
            status(anonymous.call("POST", API + "/accept", newAccount(invitation, NEW_PASSWORD)), 422);

            long employee = count("SELECT id FROM employees WHERE email=?", invitation.email());
            assertThat(text("SELECT status FROM employees WHERE id=?", employee)).isEqualTo("PENDING");
            assertThat(text("SELECT global_role FROM employees WHERE id=?", employee)).isEqualTo("EMPLOYEE");
            assertThat(text("SELECT name FROM employees WHERE id=?", employee)).isEqualTo("초대받는 직원");
            String stored = text("SELECT password_hash FROM employees WHERE id=?", employee);
            assertThat(stored).isNotEqualTo(NEW_PASSWORD);
            assertThat(encoder.matches(NEW_PASSWORD, stored)).isTrue();
            assertThat(text("SELECT CONCAT(role,'/',status) FROM branch_memberships WHERE employee_id=? AND branch_id=?", employee, first)).isEqualTo("STAFF/PENDING");
            assertThat(count("SELECT COUNT(*) FROM membership_permission_grants g JOIN branch_memberships m ON m.id=g.membership_id WHERE m.employee_id=?", employee)).isZero();
            assertThat(count("SELECT COUNT(*) FROM employee_invitations WHERE id=? AND status='ACCEPTED' AND token_hash IS NULL "
                    + "AND accepted_employee_id=? AND accepted_at IS NOT NULL", invitation.id(), employee)).isEqualTo(1);
            String requestId = accepted.headers().firstValue("X-Request-ID").orElseThrow();
            assertThat(count("SELECT COUNT(*) FROM audit_events WHERE request_id=? AND actor_employee_id=?", requestId, employee)).isEqualTo(2);
            for (String audit : jdbc.queryForList("SELECT CONCAT(COALESCE(before_json,''),after_json) FROM audit_events WHERE request_id=?", String.class, requestId)) {
                assertThat(audit).doesNotContain(invitation.token(), NEW_PASSWORD, "token", "hash");
            }
            assertThat(count("SELECT COUNT(*) FROM workspace_event_outbox WHERE branch_id=? AND entity_type='invitations' AND entity_id=?", first, invitation.id())).isPositive();

            try (var waiting = new Session(invitation.email(), NEW_PASSWORD)) {
                var me = waiting.call("GET", "/api/me", null);
                status(me, 200);
                assertThat(body(me).get("sessionMode").asString()).isEqualTo("APPROVAL_ONLY");
                status(waiting.call("GET", "/api/branches", null), 403);
                // A restricted session may still check a link; it gets the link answer, not an authorization error.
                status(waiting.call("POST", API + "/preview", Map.of("token", invitation.token())), 422);
                var pending = waiting.call("GET", API + "/approval-status", null);
                status(pending, 200);
                assertThat(body(pending).get("accountStatus").asString()).isEqualTo("승인 대기");
                assertThat(body(pending).at("/invitations/0/status").asString()).isEqualTo("승인 대기");

                try (var m = new Session(manager)) {
                    long staleVersion = dbVersion(invitation.id()) + 1;
                    var approved = approve(m, invitation.id());
                    status(approved, 200);
                    assertThat(body(approved).get("status").asString()).isEqualTo("활성");
                    assertThat(body(approved).get("employeeId").asString()).isEqualTo(Long.toString(employee));
                    // Repeating an approval returns the current result after re-checking authority.
                    var repeated = m.call("POST", API + "/" + invitation.id() + "/approve", Map.of("version", staleVersion));
                    status(repeated, 200);
                    assertThat(body(repeated)).isEqualTo(body(approved));
                }
                assertThat(text("SELECT status FROM employees WHERE id=?", employee)).isEqualTo("ACTIVE");
                assertThat(count("SELECT COUNT(*) FROM branch_memberships WHERE employee_id=? AND branch_id=? AND status='ACTIVE' "
                        + "AND approved_by=? AND approved_at IS NOT NULL", employee, first, manager)).isEqualTo(1);
                assertThat(jdbc.queryForList("SELECT p.code FROM membership_permission_grants g JOIN branch_memberships m ON m.id=g.membership_id "
                        + "JOIN permission_definitions p ON p.id=g.permission_id WHERE m.employee_id=?", String.class, employee)).containsExactly("members:write");

                // Q10: the restricted session is not promoted; it is told to log in again.
                var done = waiting.call("GET", API + "/approval-status", null);
                status(done, 200);
                assertThat(body(done).get("requiresLogin").asBoolean()).isTrue();
                status(waiting.call("GET", "/api/branches", null), 403);
            }
            try (var working = new Session(invitation.email(), NEW_PASSWORD)) {
                var me = working.call("GET", "/api/me", null);
                assertThat(body(me).get("sessionMode").asString()).isEqualTo("WORKSPACE");
                assertThat(body(me).at("/user/memberships/0/branchId").asString()).isEqualTo(Long.toString(first));
                status(working.call("GET", "/api/branches", null), 200);
            }
        }
        assertThat(output.getAll()).doesNotContain(invitation.token(), NEW_PASSWORD);
    }

    @Test void wrongExpiredConsumedCancelledAndMalformedLinksShareOneResponse() throws Exception {
        var expired = invite(owner, first, email(), "staff", List.of());
        jdbc.update("UPDATE employee_invitations SET expires_at=UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id=?", expired.id());
        var cancelled = invite(owner, first, email(), "staff", List.of());
        jdbc.update("UPDATE employee_invitations SET status='CANCELLED',token_hash=NULL,cancelled_by=?,cancelled_at=UTC_TIMESTAMP(6) WHERE id=?", owner, cancelled.id());
        var valid = invite(owner, second, email(), "staff", List.of());
        String random = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        try (var anonymous = new Session()) {
            String reference = null;
            for (String token : List.of(random, expired.token(), cancelled.token(), "short", "x".repeat(513), valid.token() + "!")) {
                var response = anonymous.call("POST", API + "/preview", Map.of("token", token));
                status(response, 422);
                var error = (tools.jackson.databind.node.ObjectNode) body(response);
                assertThat(error.get("code").asString()).isEqualTo("INVITATION_LINK_INVALID");
                error.remove("requestId");
                if (reference == null) reference = error.toString(); else assertThat(error.toString()).isEqualTo(reference);
                status(anonymous.call("POST", API + "/accept", Map.of("mode", "NEW_ACCOUNT", "token", token,
                        "email", valid.email(), "password", NEW_PASSWORD)), 422);
            }
            // The same email-bound link rejects another email without revealing whether the token was real.
            var otherEmail = anonymous.call("POST", API + "/accept", Map.of("mode", "NEW_ACCOUNT", "token", valid.token(),
                    "email", "other-" + valid.email(), "password", NEW_PASSWORD));
            status(otherEmail, 422);
            assertThat(body(otherEmail).get("code").asString()).isEqualTo("INVITATION_LINK_INVALID");
            status(anonymous.call("POST", API + "/preview", Map.of("token", valid.token(), "branchId", "1")), 422);
            status(anonymous.call("POST", API + "/accept", Map.of("mode", "NEW_ACCOUNT", "token", valid.token(),
                    "email", valid.email(), "password", NEW_PASSWORD, "role", "admin")), 422);
            status(anonymous.call("POST", API + "/accept", Map.of("mode", "OWNER", "token", valid.token(), "email", valid.email())), 422);
            for (String password : List.of("1234567", "가".repeat(25))) {
                var weak = anonymous.call("POST", API + "/accept", newAccount(valid, password));
                status(weak, 422);
                assertThat(body(weak).at("/fieldErrors/password").isMissingNode()).isFalse();
            }
            // A CSRF token is required even for anonymous link commands.
            anonymous.csrf = null;
            status(anonymous.call("POST", API + "/preview", Map.of("token", valid.token())), 403);
            status(anonymous.call("POST", API + "/accept", newAccount(valid, NEW_PASSWORD)), 403);
        }
        assertThat(count("SELECT COUNT(*) FROM employee_invitations WHERE id=? AND status='PENDING' AND token_hash IS NOT NULL", valid.id())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM employees WHERE email=?", valid.email())).isZero();
    }

    @Test void existingEmployeeAcceptsAnotherBranchWithOwnSessionAndKeepsCurrentWork() throws Exception {
        String staffPasswordHash = text("SELECT password_hash FROM employees WHERE id=?", staff);
        var invitation = invite(owner, second, employeeEmail(staff), "staff", List.of("operations:write"));
        try (var anonymous = new Session(); var other = new Session(manager); var self = new Session(staff)) {
            var preview = anonymous.call("POST", API + "/preview", Map.of("token", invitation.token()));
            status(preview, 200);
            assertThat(body(preview).get("acceptanceMode").asString()).isEqualTo("EXISTING_ACCOUNT");
            status(anonymous.call("POST", API + "/accept", existingAccount(invitation)), 401);
            var duplicate = anonymous.call("POST", API + "/accept", newAccount(invitation, NEW_PASSWORD));
            status(duplicate, 409);
            assertThat(body(duplicate).get("code").asString()).isEqualTo("INVALID_STATE");
            status(other.call("POST", API + "/accept", existingAccount(invitation)), 403);
            status(self.call("POST", API + "/accept", newAccount(invitation, NEW_PASSWORD)), 403);
            assertThat(count("SELECT COUNT(*) FROM employee_invitations WHERE id=? AND status='PENDING'", invitation.id())).isEqualTo(1);

            var accepted = self.call("POST", API + "/accept", existingAccount(invitation));
            status(accepted, 200);
            assertThat(body(accepted).get("nextAction").asString()).isEqualTo("REFRESH_APPROVAL_STATUS");
            assertThat(text("SELECT password_hash FROM employees WHERE id=?", staff)).isEqualTo(staffPasswordHash);
            assertThat(text("SELECT status FROM branch_memberships WHERE employee_id=? AND branch_id=?", staff, second)).isEqualTo("PENDING");
            // Q1: existing branch work continues while the new branch waits.
            var me = self.call("GET", "/api/me", null);
            status(me, 200);
            assertThat(body(me).get("sessionMode").asString()).isEqualTo("WORKSPACE");
            assertThat(body(me).at("/user/memberships").size()).isEqualTo(1);
            status(self.call("GET", "/api/branches/" + first, null), 200);
            assertThat(self.call("GET", "/api/branches/" + second, null).statusCode()).isIn(403, 404);
            var waiting = self.call("GET", API + "/approval-status", null);
            assertThat(body(waiting).at("/invitations/0/status").asString()).isEqualTo("승인 대기");

            try (var o = new Session(owner)) { status(approve(o, invitation.id()), 200); }
            // An existing business session sees the new branch on its next request; no re-login is needed.
            var after = self.call("GET", "/api/me", null);
            assertThat(body(after).at("/user/memberships").size()).isEqualTo(2);
            status(self.call("GET", "/api/branches/" + second, null), 200);
        }
    }

    @Test void revokedMembershipIsRecoveredThroughSameRowAndManagerInvitationNeedsOwner() throws Exception {
        long membership = count("SELECT id FROM branch_memberships WHERE employee_id=? AND branch_id=?", staff, first);
        jdbc.update("UPDATE branch_memberships SET status='REVOKED',version=version+1 WHERE id=?", membership);
        long revokedVersion = count("SELECT version FROM branch_memberships WHERE id=?", membership);
        var recovery = invite(owner, first, employeeEmail(staff), "admin", List.of("people:manage"));
        try (var self = new Session(staff)) { status(self.call("POST", API + "/accept", existingAccount(recovery)), 200); }
        assertThat(count("SELECT COUNT(*) FROM branch_memberships WHERE employee_id=? AND branch_id=?", staff, first)).isEqualTo(1);
        assertThat(text("SELECT CONCAT(role,'/',status) FROM branch_memberships WHERE id=?", membership)).isEqualTo("MANAGER/PENDING");
        try (var m = new Session(manager); var o = new Session(owner)) {
            // Q4/Q11: a branch manager can neither approve a MANAGER invitation nor recover a manager.
            status(approve(m, recovery.id()), 403);
            assertThat(text("SELECT status FROM employee_invitations WHERE id=?", recovery.id())).isEqualTo("ACCEPTED");
            status(approve(o, recovery.id()), 200);
        }
        assertThat(text("SELECT CONCAT(role,'/',status) FROM branch_memberships WHERE id=?", membership)).isEqualTo("MANAGER/ACTIVE");
        assertThat(count("SELECT version FROM branch_memberships WHERE id=?", membership)).isGreaterThan(revokedVersion);
        assertThat(count("SELECT COUNT(*) FROM membership_permission_grants WHERE membership_id=?", membership)).isEqualTo(1);
    }

    @Test void approvalRejectsUnacceptedTerminalAndRestrictedAccountsWithoutReactivation() throws Exception {
        var unaccepted = invite(owner, first, email(), "staff", List.of());
        var rejected = invite(owner, first, email(), "staff", List.of());
        var suspended = invite(owner, second, email(), "staff", List.of());
        try (var anonymous = new Session()) {
            status(anonymous.call("POST", API + "/accept", newAccount(rejected, NEW_PASSWORD)), 200);
            status(anonymous.call("POST", API + "/accept", newAccount(suspended, NEW_PASSWORD)), 200);
        }
        try (var o = new Session(owner); var s = new Session(staff)) {
            var notAccepted = approve(o, unaccepted.id());
            status(notAccepted, 409);
            assertThat(body(notAccepted).get("code").asString()).isEqualTo("INVALID_STATE");
            // STAFF holding no administrator role cannot approve.
            status(approve(s, rejected.id()), 403);
            status(o.call("POST", API + "/" + rejected.id() + "/reject", Map.of("version", dbVersion(rejected.id()) + 1, "reason", "확인 불가")), 200);
            status(approve(o, rejected.id()), 409);
            var stale = o.call("POST", API + "/" + suspended.id() + "/approve", Map.of("version", dbVersion(suspended.id())));
            status(stale, 409);
            assertThat(body(stale).get("code").asString()).isEqualTo("VERSION_CONFLICT");
            long account = count("SELECT id FROM employees WHERE email=?", suspended.email());
            jdbc.update("UPDATE employees SET status='SUSPENDED' WHERE id=?", account);
            status(approve(o, suspended.id()), 409);
            assertThat(text("SELECT status FROM employees WHERE id=?", account)).isEqualTo("SUSPENDED");
            assertThat(text("SELECT status FROM branch_memberships WHERE employee_id=?", account)).isEqualTo("PENDING");
            assertThat(text("SELECT status FROM employee_invitations WHERE id=?", suspended.id())).isEqualTo("ACCEPTED");
        }
    }

    @Test void concurrentAcceptancesConsumeOneLinkAndApprovalRacesRejectionOnce() throws Exception {
        var invitation = invite(owner, first, email(), "staff", List.of());
        var results = race(8, () -> {
            try (var anonymous = new Session()) {
                return anonymous.call("POST", API + "/accept", newAccount(invitation, NEW_PASSWORD)).statusCode();
            }
        });
        assertThat(results).containsOnly(200, 422);
        assertThat(results.stream().filter(code -> code == 200)).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM employees WHERE email=?", invitation.email())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM branch_memberships m JOIN employees e ON e.id=m.employee_id WHERE e.email=?", invitation.email())).isEqualTo(1);

        long version = dbVersion(invitation.id()) + 1;
        try (var o = new Session(owner); var m = new Session(manager)) {
            var decisions = race(2, List.of(
                    () -> o.call("POST", API + "/" + invitation.id() + "/approve", Map.of("version", version)).statusCode(),
                    () -> m.call("POST", API + "/" + invitation.id() + "/reject", Map.of("version", version, "reason", "중복 처리")).statusCode()));
            assertThat(decisions).containsExactlyInAnyOrder(200, 409);
        }
        String state = text("SELECT status FROM employee_invitations WHERE id=?", invitation.id());
        String membership = text("SELECT m.status FROM branch_memberships m JOIN employees e ON e.id=m.employee_id WHERE e.email=?", invitation.email());
        String account = text("SELECT status FROM employees WHERE email=?", invitation.email());
        if ("APPROVED".equals(state)) assertThat(List.of(membership, account)).containsExactly("ACTIVE", "ACTIVE");
        else assertThat(List.of(state, membership, account)).containsExactly("REJECTED", "REVOKED", "PENDING");
    }

    @Test void acceptanceAndApprovalWritesCommitOrRollBackTogether() throws Exception {
        var invitation = invite(owner, first, email(), "staff", List.of("members:write"));
        String request = "acceptance-rollback-" + UUID.randomUUID();
        var transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(tx -> {
            acceptance.accept(newAccount(invitation, NEW_PASSWORD), null, null, request);
            assertThat(count("SELECT COUNT(*) FROM employees WHERE email=?", invitation.email())).isEqualTo(1);
            tx.setRollbackOnly();
        });
        assertThat(count("SELECT COUNT(*) FROM employees WHERE email=?", invitation.email())).isZero();
        assertThat(count("SELECT COUNT(*) FROM employee_invitations WHERE id=? AND status='PENDING' AND token_hash IS NOT NULL", invitation.id())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE request_id=?", request)).isZero();

        try (var anonymous = new Session()) { status(anonymous.call("POST", API + "/accept", newAccount(invitation, NEW_PASSWORD)), 200); }
        long employee = count("SELECT id FROM employees WHERE email=?", invitation.email());
        long version = dbVersion(invitation.id()) + 1;
        transaction.executeWithoutResult(tx -> {
            invitations.approve(owner, invitation.id(), "all", Map.of("version", version), request);
            assertThat(text("SELECT status FROM employees WHERE id=?", employee)).isEqualTo("ACTIVE");
            tx.setRollbackOnly();
        });
        assertThat(text("SELECT status FROM employees WHERE id=?", employee)).isEqualTo("PENDING");
        assertThat(text("SELECT status FROM branch_memberships WHERE employee_id=?", employee)).isEqualTo("PENDING");
        assertThat(count("SELECT COUNT(*) FROM membership_permission_grants g JOIN branch_memberships m ON m.id=g.membership_id WHERE m.employee_id=?", employee)).isZero();
        assertThat(text("SELECT status FROM employee_invitations WHERE id=?", invitation.id())).isEqualTo("ACCEPTED");
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE request_id=?", request)).isZero();
    }

    private List<Integer> race(int count, Callable<Integer> task) throws Exception {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) tasks.add(task);
        return race(count, tasks);
    }

    private List<Integer> race(int count, List<Callable<Integer>> tasks) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(count)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (var task : tasks) futures.add(pool.submit(() -> { start.await(); return task.call(); }));
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(60, TimeUnit.SECONDS));
            return results;
        }
    }
}
