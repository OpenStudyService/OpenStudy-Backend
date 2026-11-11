package com.openstudy.integration.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #102 with the prod log format (M11-D04): request lines are JSON with request ID, employee, route, status and duration,
 * and never a password, session or CSRF token. The DB health is forced down here: readiness fails, liveness does not.
 */
@MySqlIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "logging.structured.format.console=logstash")
@Import(StructuredLogAndReadinessTests.DatabaseDown.class)
class StructuredLogAndReadinessTests {
    static final String PASSWORD = "structured-log-password";

    /** Replaces the auto-configured DB health (it backs off when a bean of this name exists). */
    @TestConfiguration
    static class DatabaseDown {
        @Bean HealthIndicator dbHealthContributor() { return () -> Health.down().withDetail("reason", "test").build(); }
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    Long employee;

    @AfterEach void clean() {
        if (employee == null) return;
        jdbc.update("DELETE FROM audit_events WHERE actor_employee_id=? OR (entity_type='employee' AND entity_id=?)", employee, employee);
        jdbc.update("DELETE FROM employees WHERE id=?", employee);
    }

    HttpResponse<String> send(HttpClient client, String method, String path, String csrf, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (csrf != null) request.header("X-CSRF-Token", csrf);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void readinessFailsWhileTheDatabaseIsDownAndLivenessDoesNot() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var readiness = send(client, "GET", "/actuator/health/readiness", null, null);
            assertThat(readiness.statusCode()).isEqualTo(503);
            assertThat(readiness.body()).isEqualTo("{\"status\":\"DOWN\"}");
            assertThat(send(client, "GET", "/actuator/health/liveness", null, null).statusCode()).isEqualTo(200);
        }
    }

    @Test void requestLinesAreJsonWithTheEmployeeAndNoSecrets(CapturedOutput output) throws Exception {
        String email = UUID.randomUUID() + "@structured-log.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,'로그 직원','로그','EMPLOYEE','ACTIVE')",
                email, passwords.encode(PASSWORD));
        employee = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (var client = HttpClient.newBuilder().cookieHandler(cookies).build()) {
            String csrf = json.readTree(send(client, "GET", "/api/auth/csrf", null, null).body()).get("token").asString();
            assertThat(send(client, "POST", "/api/auth/login", csrf,
                    json.writeValueAsString(java.util.Map.of("email", email, "password", PASSWORD))).statusCode()).isEqualTo(200);
            String session = cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("SESSION")).findFirst()
                    .orElseThrow().getValue();
            var me = send(client, "GET", "/api/me", null, null);
            assertThat(me.statusCode()).isEqualTo(200);
            String id = me.headers().firstValue("X-Request-ID").orElseThrow();

            String line = output.getAll().lines().filter(l -> l.contains("\"requestId\":\"" + id + "\"") && l.contains("\"route\""))
                    .findFirst().orElseThrow(() -> new AssertionError("no JSON request line for " + id + "\n" + output.getAll()));
            var fields = json.readTree(line);
            assertThat(fields.get("route").asString()).isEqualTo("/api/me");
            assertThat(fields.get("status").asInt()).isEqualTo(200);
            assertThat(fields.get("employeeId").asLong()).isEqualTo(employee);
            assertThat(fields.has("durationMs")).isTrue();
            assertThat(output.getAll()).doesNotContain(PASSWORD, session, csrf, email);
        }
    }
}
