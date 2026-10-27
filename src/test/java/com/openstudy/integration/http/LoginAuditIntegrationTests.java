package com.openstudy.integration.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import tools.jackson.databind.ObjectMapper;

import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@MySqlIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class LoginAuditIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    private static final String PASSWORD = "integration-password-123";
    private static final String WRONG = "wrong-password-456";
    private String marker;
    private String email;
    private long employeeId;
    private long branchId;
    private HttpClient client;
    private CookieManager cookies;

    record Row(Long actor, String entityType, Long entity, String action, String reason, String result,
            String requestId, String before, String after, Long branch) {}

    @BeforeEach
    void prepare() {
        marker = UUID.randomUUID().toString().substring(0, 12);
        email = marker + "@example.com";
        jdbc.update("INSERT INTO employees (email,password_hash,name,nickname,status) VALUES (?,?,'감사 직원','닉네임','ACTIVE')",
                email, passwords.encode(PASSWORD));
        employeeId = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        jdbc.update("INSERT INTO branches (code,name,address) VALUES (?,'감사 지점','서울')", "L-" + marker);
        branchId = jdbc.queryForObject("SELECT id FROM branches WHERE code=?", Long.class, "L-" + marker);
        jdbc.update("INSERT INTO branch_memberships (employee_id,branch_id,role,status) VALUES (?,?,'MANAGER','ACTIVE')", employeeId, branchId);
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    @AfterEach
    void cleanUp() {
        if (client != null) client.close();
        jdbc.update("DELETE FROM audit_events WHERE entity_type='employee' AND (entity_id=? OR actor_employee_id=?)", employeeId, employeeId);
        jdbc.update("DELETE FROM branch_memberships WHERE employee_id=?", employeeId);
        jdbc.update("DELETE FROM employees WHERE id=?", employeeId);
        jdbc.update("DELETE FROM branches WHERE id=?", branchId);
    }

    @Test
    void successLinksActorTargetAndRequestId() throws Exception {
        var response = login(email, PASSWORD);
        assertThat(response.statusCode()).isEqualTo(200);
        var row = row(requestId(response));
        assertThat(row.actor()).isEqualTo(employeeId);
        assertThat(row.entityType()).isEqualTo("employee");
        assertThat(row.entity()).isEqualTo(employeeId);
        assertThat(row.action()).isEqualTo("LOGIN_SUCCEEDED");
        assertThat(row.result()).isEqualTo("SUCCESS");
        assertThat(row.reason()).isNull();
    }

    @Test
    void wrongPasswordLinksTheTargetButNotAnActor() throws Exception {
        var response = login(email, WRONG);
        assertThat(response.statusCode()).isEqualTo(401);
        var row = row(requestId(response));
        assertThat(row.actor()).isNull();
        assertThat(row.entity()).isEqualTo(employeeId);
        assertThat(row.action()).isEqualTo("LOGIN_FAILED");
        assertThat(row.reason()).isEqualTo("BAD_CREDENTIALS");
        assertThat(row.result()).isEqualTo("FAILED");
    }

    @Test
    void unknownAccountIsRecordedWithoutAnyEmployeeReference() throws Exception {
        var response = login("missing-" + marker + "@example.com", WRONG);
        assertThat(response.statusCode()).isEqualTo(401);
        var row = row(requestId(response));
        assertThat(row.actor()).isNull();
        assertThat(row.entity()).isNull();
        assertThat(row.branch()).isNull();
        assertThat(row.action()).isEqualTo("LOGIN_FAILED");
        assertThat(row.reason()).isEqualTo("BAD_CREDENTIALS");
        jdbc.update("DELETE FROM audit_events WHERE request_id=?", requestId(response));
    }

    @Test
    void suspendedAccountAndOverlongPasswordAreFailures() throws Exception {
        var overlong = login(email, "가".repeat(25));
        assertThat(overlong.statusCode()).isEqualTo(422);
        assertThat(row(requestId(overlong)).reason()).isEqualTo("INVALID_INPUT");

        jdbc.update("UPDATE employees SET status='SUSPENDED' WHERE id=?", employeeId);
        var suspended = login(email, PASSWORD);
        assertThat(suspended.statusCode()).isEqualTo(401);
        var row = row(requestId(suspended));
        assertThat(row.actor()).isNull();
        assertThat(row.entity()).isEqualTo(employeeId);
        assertThat(row.reason()).isEqualTo("ACCOUNT_DISABLED");
    }

    @Test
    void repeatedRateLimitedAttemptsWriteOneDeniedRowPerWindow() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(login(email, WRONG).statusCode()).isEqualTo(401);
        }
        var first = login(email, PASSWORD);
        var second = login(email, PASSWORD);
        assertThat(first.statusCode()).isEqualTo(429);
        assertThat(second.statusCode()).isEqualTo(429);
        assertThat(json.readTree(first.body()).get("requestId").asString()).isEqualTo(requestId(first));

        var row = row(requestId(first));
        assertThat(row.actor()).isNull();
        assertThat(row.entity()).isEqualTo(employeeId);
        assertThat(row.action()).isEqualTo("LOGIN_RATE_LIMITED");
        assertThat(row.reason()).isEqualTo("ACCOUNT_LIMIT");
        assertThat(row.result()).isEqualTo("DENIED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE request_id=?", Integer.class, requestId(second))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE entity_type='employee' AND entity_id=? AND action='LOGIN_FAILED'",
                Integer.class, employeeId)).isEqualTo(10);
    }

    @Test
    void auditRowsAndLogsCarryNoCredentialsSessionOrAddressMaterial(CapturedOutput output) throws Exception {
        String csrf = csrf();
        var failed = login(email, WRONG, csrf);
        var succeeded = login(email, PASSWORD, csrf);
        assertThat(succeeded.statusCode()).isEqualTo(200);
        String session = cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("SESSION"))
                .findFirst().orElseThrow().getValue();
        String hash = jdbc.queryForObject("SELECT password_hash FROM employees WHERE id=?", String.class, employeeId);

        List<String> forbidden = List.of(PASSWORD, WRONG, email, marker, hash, session, csrf);
        for (var response : List.of(failed, succeeded)) {
            var row = row(requestId(response));
            assertThat(row.before()).isNull();
            assertThat(row.after()).isNull();
            assertThat(String.valueOf(row)).doesNotContain(forbidden).doesNotContain("127.0.0.1");
        }
        // Context startup may log container addresses, so the client address is checked on the row only.
        assertThat(output.getAll()).doesNotContain(forbidden);
        assertThat(output.getAll()).contains("requestId=" + requestId(failed));
    }

    private Row row(String requestId) {
        return jdbc.queryForObject("SELECT actor_employee_id,entity_type,entity_id,action,reason,result,request_id,before_json,after_json,branch_id "
                + "FROM audit_events WHERE request_id=?", (rs, n) -> new Row(
                rs.getObject("actor_employee_id", Long.class), rs.getString("entity_type"), rs.getObject("entity_id", Long.class),
                rs.getString("action"), rs.getString("reason"), rs.getString("result"), rs.getString("request_id"),
                rs.getString("before_json"), rs.getString("after_json"), rs.getObject("branch_id", Long.class)), requestId);
    }

    private String requestId(HttpResponse<String> response) {
        return response.headers().firstValue("X-Request-ID").orElseThrow();
    }

    private String csrf() throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).get("token").asString();
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return login(email, password, csrf());
    }

    private HttpResponse<String> login(String email, String password, String csrf) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json").header("X-CSRF-Token", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("email", email, "password", password))))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
