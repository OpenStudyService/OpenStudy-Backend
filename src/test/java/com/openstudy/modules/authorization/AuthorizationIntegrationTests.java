package com.openstudy.modules.authorization;

import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.*;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.support.database.MySqlIntegrationTest;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.common.exception.ApiException;
import static org.assertj.core.api.Assertions.*;

@MySqlIntegrationTest
@Import(AuthorizationIntegrationTests.ProbeController.class)
class AuthorizationIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthorizationService policy;
    @Autowired AuthorizedCommands commands;
    @Autowired TransactionTemplate transactions;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    private String hash;
    private final List<Long> people = new ArrayList<>();
    private final List<Long> branches = new ArrayList<>();
    private long owner, manager, staff, first, second;

    @BeforeEach
    void fixture() {
        hash = passwords.encode("policy-test-password");
        owner = person("OWNER"); manager = person("EMPLOYEE"); staff = person("EMPLOYEE");
        first = branch(); second = branch();
        membership(manager, first, "MANAGER"); membership(staff, first, "STAFF");
        grant(manager, "people:manage"); grant(manager, "operations:write");
        grant(staff, "operations:write"); grant(staff, "people:manage");
    }
    @AfterEach
    void clean() {
        for (long person : people) jdbc.update("DELETE FROM branch_memberships WHERE employee_id=?", person);
        for (long person : people) jdbc.update("DELETE FROM employees WHERE id=?", person);
        for (long branch : branches) jdbc.update("DELETE FROM branches WHERE id=?", branch);
        people.clear(); branches.clear();
    }
    private long person(String role) {
        String email = UUID.randomUUID()+"@policy.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES (?,?,'정책','정책',?,'ACTIVE')", email, hash, role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email); people.add(id); return id;
    }
    private long branch() {
        String code = UUID.randomUUID().toString().substring(0, 20);
        jdbc.update("INSERT INTO branches(code,name,address) VALUES (?,'정책 지점','서울')", code);
        long id = jdbc.queryForObject("SELECT id FROM branches WHERE code=?", Long.class, code); branches.add(id); return id;
    }
    private void membership(long person, long branch, String role) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES (?,?,?,'ACTIVE')", person, branch, role);
    }
    private void grant(long person, String code) {
        jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id FROM branch_memberships m "
                + "JOIN permission_definitions p ON p.code=? WHERE m.employee_id=?", code, person);
    }
    @Test
    void currentDatabaseStateNotSessionGrantsControlsScopeSharedProductsAndStoppedBranches() {
        assertThat(policy.scope(staff, "all", BranchAction.READ)).containsExactly(first);
        assertThatThrownBy(() -> policy.scope(staff, Long.toString(second), BranchAction.READ)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> policy.requireSharedProduct(staff, Set.of(first, second), Set.of(first), false))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> policy.requireSharedProduct(staff, Set.of(first), Set.of(first, second), false))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);
        assertThat(policy.scope(staff, "all", BranchAction.READ)).containsExactly(first);
        assertThatThrownBy(() -> commands.inBranches(owner, Set.of(first), BranchAction.NEW_PAYMENT, () -> true))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        commands.membershipChange(owner, staff, first, "STAFF", "REVOKED", () -> jdbc.update(
                "UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=? AND branch_id=?", staff, first));
        assertThat(policy.scope(staff, "all", BranchAction.READ)).isEmpty();
    }
    @Test
    void concurrentRemovalOfLastOwnerIsRolledBackOnBothConnections() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var task = (java.util.concurrent.Callable<String>) () -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    commands.accountChange(owner, owner, () -> jdbc.update("UPDATE employees SET status='SUSPENDED' WHERE id=?", owner));
                    return "unexpected";
                } catch (ApiException denied) { return denied.error("test").code(); }
            };
            var one = pool.submit(task); var two = pool.submit(task); start.countDown();
            assertThat(one.get(15, TimeUnit.SECONDS)).isEqualTo("LAST_ADMIN_REQUIRED");
            assertThat(two.get(15, TimeUnit.SECONDS)).isEqualTo("LAST_ADMIN_REQUIRED");
        }
        assertThat(jdbc.queryForObject("SELECT status FROM employees WHERE id=?", String.class, owner)).isEqualTo("ACTIVE");
    }
    @Test
    void twoManagersCannotConcurrentlyRevokeEachOtherEvenWithoutAnOwner() throws Exception {
        long another = person("EMPLOYEE"); membership(another, first, "MANAGER"); grant(another, "people:manage");
        jdbc.update("DELETE FROM employees WHERE id=?", owner);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> {
                try { if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                assertThatThrownBy(() -> commands.membershipChange(manager, another, first, "STAFF", "REVOKED", () -> true))
                        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            });
            var two = pool.submit(() -> {
                try { if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                assertThatThrownBy(() -> commands.membershipChange(another, manager, first, "STAFF", "REVOKED", () -> true))
                        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            });
            start.countDown(); one.get(15, TimeUnit.SECONDS); two.get(15, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM branch_memberships WHERE branch_id=? AND role='MANAGER' AND status='ACTIVE'", Long.class, first)).isEqualTo(2);
    }
    @Test
    void oldRepeatableReadSnapshotCannotAuthorizeAfterRevocationCommitted() throws Exception {
        CountDownLatch oldRead = new CountDownLatch(1), revoked = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var writer = pool.submit(() -> transactions.execute(status -> {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM membership_permission_grants g JOIN branch_memberships m "
                        + "ON m.id=g.membership_id WHERE m.employee_id=?", Long.class, staff)).isPositive();
                oldRead.countDown();
                try { if (!revoked.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                assertThatThrownBy(() -> commands.inBranches(staff, Set.of(first), BranchAction.EDIT_PRODUCT,
                        () -> jdbc.update("UPDATE branches SET name='must-not-write' WHERE id=?", first)))
                        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                // Joined transaction correctly becomes rollback-only after denied command.
                status.setRollbackOnly();
                return true;
            }));
            assertThat(oldRead.await(10, TimeUnit.SECONDS)).isTrue();
            commands.membershipChange(owner, staff, first, "STAFF", "ACTIVE", () -> jdbc.update(
                    "DELETE g FROM membership_permission_grants g JOIN branch_memberships m ON m.id=g.membership_id WHERE m.employee_id=?", staff));
            revoked.countDown();
            assertThat(writer.get(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT name FROM branches WHERE id=?", String.class, first)).isEqualTo("정책 지점");
    }
    @Test
    void writeHoldsAuthorizationUntilCommitThenQueuedRevocationTakesEffect() throws Exception {
        CountDownLatch writing = new CountDownLatch(1), finish = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var write = pool.submit(() -> commands.inBranches(staff, Set.of(first), BranchAction.EDIT_PRODUCT,
                    () -> assertThat(jdbc.queryForObject("SELECT id FROM branches WHERE id=? FOR UPDATE", Long.class, first)).isEqualTo(first), state -> {
                policy.requireSharedProduct(state, Set.of(first), Set.of(first), false);
                writing.countDown();
                try { if (!finish.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException exception) { throw new IllegalStateException(exception); }
                return jdbc.update("UPDATE branches SET name='committed-write' WHERE id=?", first);
            }));
            assertThat(writing.await(10, TimeUnit.SECONDS)).isTrue();
            var revoke = pool.submit(() -> commands.membershipChange(owner, staff, first, "STAFF", "REVOKED", () -> jdbc.update(
                    "UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=? AND branch_id=?", staff, first)));
            finish.countDown();
            assertThat(write.get(15, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(revoke.get(15, TimeUnit.SECONDS)).isEqualTo(1);
            assertThatThrownBy(() -> commands.inBranches(staff, Set.of(first), BranchAction.EDIT_PRODUCT, () -> true))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        }
    }

    @RestController
    static class ProbeController {
        private final AuthorizationService authorization;
        private final AuthorizedCommands commands;
        ProbeController(AuthorizationService authorization, AuthorizedCommands commands) {
            this.authorization = authorization; this.commands = commands;
        }
        @GetMapping("/api/policy-probe")
        Set<Long> get(Authentication actor, @RequestParam(defaultValue="all") String scope) {
            return authorization.scope(Long.parseLong(actor.getName()), scope, BranchAction.READ);
        }
        @PostMapping("/api/policy-probe/{branch}")
        boolean write(Authentication actor, @PathVariable long branch, @RequestBody Map<String, Object> body) {
            Set<Long> actual = Set.of(branch);
            // The path's persisted scope must be allowed independently of a forged body scope/canEdit flag.
            return commands.inBranches(Long.parseLong(actor.getName()), actual, BranchAction.EDIT_PRODUCT, () -> true);
        }
    }
    @Test
    void liveHttpScopePathAndBodyManipulationCannotGrantAccessAndNewRequestsSeeRevocation() throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (var client = HttpClient.newBuilder().cookieHandler(cookies).build()) {
            String token = json.readTree(http(client, "GET", "/api/auth/csrf", null, null).body()).get("token").asString();
            String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, staff);
            assertThat(http(client, "POST", "/api/auth/login", token,
                    Map.of("email", email, "password", "policy-test-password")).statusCode()).isEqualTo(200);
            token = json.readTree(http(client, "GET", "/api/auth/csrf", null, null).body()).get("token").asString();
            assertThat(json.readTree(http(client, "GET", "/api/policy-probe?scope=all", null, null).body()).size()).isEqualTo(1);
            assertThat(http(client, "GET", "/api/policy-probe?scope="+second, null, null).statusCode()).isEqualTo(403);
            assertThat(http(client, "GET", "/api/employees?scope="+first, null, null).statusCode()).isEqualTo(403);
            assertThat(http(client, "POST", "/api/policy-probe/"+second+"?scope="+first, token,
                    Map.of("branchIds", List.of(first), "canEdit", true)).statusCode()).isEqualTo(403);
            assertThat(http(client, "POST", "/api/policy-probe/"+first, token, Map.of()).statusCode()).isEqualTo(200);
            commands.membershipChange(owner, staff, first, "STAFF", "ACTIVE", () -> jdbc.update(
                    "DELETE g FROM membership_permission_grants g JOIN branch_memberships m ON m.id=g.membership_id WHERE m.employee_id=?", staff));
            var denied = http(client, "POST", "/api/policy-probe/"+first, token, Map.of("canEdit", true));
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(denied.body()).contains("FORBIDDEN", "requestId").doesNotContain("password_hash", hash);
        }
    }
    private HttpResponse<String> http(HttpClient client, String method, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
        if (token != null) request.header("X-CSRF-Token", token);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
