package com.openstudy.integration.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.support.database.MySqlIntegrationTest;
import com.openstudy.config.LocalOwnerBootstrap;
import com.openstudy.modules.employee.service.EmployeeAccountService;

import static org.assertj.core.api.Assertions.assertThat;

@MySqlIntegrationTest
class AuthenticationIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired MySQLContainer mysql;
    @Autowired SessionRepository<? extends Session> sessions;
    @Autowired EmployeeAccountService employees;

    private String email;
    private long employeeId;
    private long branchId;
    private long hiddenBranchId;
    private long permissionId;
    private String passwordHash;
    private long localOwnerId;
    private CookieManager cookies;
    private HttpClient client;
    private static final String PASSWORD = "integration-password-123";

    @BeforeEach
    void prepareOnlyInTheContainerDatabase() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("openstudy_test");
            assertThat(connection.getMetaData().getURL().split("\\?")[0]).isEqualTo(mysql.getJdbcUrl().split("\\?")[0]);
        }
        String marker = UUID.randomUUID().toString().substring(0, 12);
        email = marker + "@example.com";
        passwordHash = passwords.encode(PASSWORD);
        jdbc.update("INSERT INTO employees (email,password_hash,name,nickname,status) VALUES (?,?,'테스트 직원','닉네임','ACTIVE')",
                email, passwordHash);
        employeeId = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        jdbc.update("INSERT INTO branches (code,name,address) VALUES (?,'로그인 지점','서울')", "A-" + marker);
        branchId = jdbc.queryForObject("SELECT id FROM branches WHERE code=?", Long.class, "A-" + marker);
        jdbc.update("INSERT INTO branches (code,name,address) VALUES (?,'비공개 지점','서울')", "B-" + marker);
        hiddenBranchId = jdbc.queryForObject("SELECT id FROM branches WHERE code=?", Long.class, "B-" + marker);
        jdbc.update("INSERT INTO branch_memberships (employee_id,branch_id,role,status) VALUES (?,?,'MANAGER','ACTIVE')", employeeId, branchId);
        jdbc.update("INSERT INTO permission_definitions (code,description) VALUES (?,'integration test')", "test:" + marker);
        permissionId = jdbc.queryForObject("SELECT id FROM permission_definitions WHERE code=?", Long.class, "test:" + marker);
        jdbc.update("INSERT INTO membership_permission_grants (membership_id,permission_id) "
                + "SELECT id,? FROM branch_memberships WHERE employee_id=?", permissionId, employeeId);
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    @AfterEach
    void cleanUpOnlyOurFixture() {
        if (client != null) client.close();
        jdbc.update("DELETE FROM branch_memberships WHERE employee_id=?", employeeId);
        jdbc.update("DELETE FROM employees WHERE id=?", employeeId);
        jdbc.update("DELETE FROM employees WHERE id=?", localOwnerId);
        jdbc.update("DELETE FROM branches WHERE id IN (?,?)", branchId, hiddenBranchId);
        jdbc.update("DELETE FROM permission_definitions WHERE id=?", permissionId);
    }

    @Test
    void logsInWithDatabaseCredentialsAndPersistsRotatedSessionThroughJdbc() throws Exception {
        String token = csrf();
        String before = sessionId();
        var response = post("/api/auth/login", token, Map.of("email", email, "password", PASSWORD));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("password", PASSWORD, passwordHash);
        var current = json.readTree(response.body());
        assertThat(current.at("/user/id").asString()).isEqualTo(Long.toString(employeeId));
        assertThat(current.at("/user/version").asInt()).isEqualTo(1);
        assertThat(current.at("/user/memberships/0/role").asString()).isEqualTo("admin");
        assertThat(current.at("/user/memberships/0/permissions/0").asString()).startsWith("test:");
        assertThat(current.get("branches").size()).isEqualTo(1);
        assertThat(current.at("/branches/0/id").asString()).isEqualTo(Long.toString(branchId));
        String after = sessionId();
        assertThat(after).isNotEqualTo(before);
        assertThat(sessions.findById(before)).isNull();
        var persisted = sessions.findById(after);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getMaxInactiveInterval().toMinutes()).isEqualTo(60);
        assertThat(persisted.getAttributeNames()).contains("SPRING_SECURITY_CONTEXT", "authenticatedAt");
        var context = persisted.<org.springframework.security.core.context.SecurityContext>getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context.getAuthentication().getCredentials()).isNull();
        assertThat(((org.springframework.security.core.userdetails.UserDetails) context.getAuthentication().getPrincipal()).getPassword()).isNull();
        assertThat(jdbc.queryForObject("SELECT last_login_at IS NOT NULL FROM employees WHERE id=?", Boolean.class, employeeId)).isTrue();
        var me = get("/api/me");
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json.readTree(me.body()).at("/user/email").asString()).isEqualTo(email);
        // Local preview handlers must be absent from the regular application profile.
        assertThat(get("/api/home").statusCode()).isEqualTo(404);

        var oldTokenLogout = post("/api/auth/logout", token, Map.of());
        assertThat(oldTokenLogout.statusCode()).isEqualTo(403);
        var logout = post("/api/auth/logout", csrf(), Map.of());
        assertThat(logout.statusCode()).isEqualTo(200);
        assertThat(sessions.findById(after)).isNull();
        assertThat(get("/api/me").statusCode()).isEqualTo(401);
        assertThat(post("/api/auth/logout", null, Map.of()).statusCode()).isEqualTo(200);
    }

    @Test
    void reloadsAccountStatusAndRevokedMembershipsFromDatabase() throws Exception {
        assertThat(post("/api/auth/login", csrf(), Map.of("email", email, "password", PASSWORD)).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE branch_memberships SET status='REVOKED' WHERE employee_id=?", employeeId);
        var me = json.readTree(get("/api/me").body());
        assertThat(me.get("branches").size()).isZero();
        assertThat(me.at("/user/memberships").size()).isZero();
        String id = sessionId();
        jdbc.update("UPDATE employees SET status='SUSPENDED' WHERE id=?", employeeId);
        assertThat(get("/api/me").statusCode()).isEqualTo(401);
        assertThat(sessions.findById(id)).isNull();
    }

    @Test
    void bootstrapCreatesOneHashedOwnerAndNeverOverwritesExistingOwnerCredentials() throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employees WHERE global_role='OWNER'", Integer.class)).isZero();
        String ownerEmail = "owner-" + email;
        new LocalOwnerBootstrap(employees, passwords, ownerEmail, PASSWORD).run();
        var owner = employees.byEmail(ownerEmail);
        localOwnerId = owner.id();
        assertThat(owner.owner()).isTrue();
        assertThat(owner.status()).isEqualTo("ACTIVE");
        assertThat(owner.passwordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, owner.passwordHash())).isTrue();
        new LocalOwnerBootstrap(employees, passwords, "another-" + email, "another-valid-password").run();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employees WHERE global_role='OWNER'", Integer.class)).isEqualTo(1);
        assertThat(employees.byEmail(ownerEmail).passwordHash()).isEqualTo(owner.passwordHash());
        assertThat(post("/api/auth/login", csrf(), Map.of("email", ownerEmail, "password", PASSWORD)).statusCode()).isEqualTo(200);
        assertThat(json.readTree(get("/api/me").body()).at("/user/owner").asBoolean()).isTrue();
    }

    private String csrf() throws Exception {
        var response = get("/api/auth/csrf");
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).get("token").asString();
    }

    private String sessionId() {
        String value = cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("SESSION"))
                .findFirst().orElseThrow().getValue().replace("\"", "");
        return new String(Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String csrf, Map<String, String> body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (csrf != null) request.header("X-CSRF-Token", csrf);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
