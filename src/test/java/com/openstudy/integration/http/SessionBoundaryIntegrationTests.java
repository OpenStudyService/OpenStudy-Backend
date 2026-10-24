package com.openstudy.integration.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.modules.auth.service.SessionBoundaryPolicy;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@MySqlIntegrationTest
@Import({SessionBoundaryIntegrationTests.TimeConfiguration.class, SessionBoundaryIntegrationTests.ActivityProbe.class})
class SessionBoundaryIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired MutableClock clock;
    @Autowired MySQLContainer mysql;
    @Autowired SessionRepository<? extends Session> sessions;
    CookieManager cookies;
    HttpClient client;
    String email;
    long employeeId;
    long branchId;
    long otherEmployeeId;
    static final String PASSWORD = "integration-password-123";

    static class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
        void advance(long seconds) { now.updateAndGet(value -> value.plusSeconds(seconds)); }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(); }
    }
    @RestController
    static class ActivityProbe {
        @GetMapping("/api/session-test/activity") Map<String, Boolean> activity() { return Map.of("ok", true); }
        @GetMapping("/api/session-test/denied") ResponseEntity<Void> denied() { return ResponseEntity.status(403).build(); }
    }

    @BeforeEach void fixtureOnlyInContainerDatabase() {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("openstudy_test");
            assertThat(connection.getMetaData().getURL().split("\\?")[0]).isEqualTo(mysql.getJdbcUrl().split("\\?")[0]);
        } catch (java.sql.SQLException failure) { throw new AssertionError(failure); }
        clock.now.set(Instant.now());
        String marker = UUID.randomUUID().toString().substring(0, 12);
        email = marker + "@example.com";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,status) VALUES (?,?,'제한 직원','업무별명','PENDING')",
                email, passwords.encode(PASSWORD));
        employeeId = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,status) VALUES (?,'unused','다른 직원','다른 직원','ACTIVE')", "other-" + email);
        otherEmployeeId = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, "other-" + email);
        jdbc.update("INSERT INTO branches(code,name,address) VALUES (?,'승인 지점','서울')", marker);
        branchId = jdbc.queryForObject("SELECT id FROM branches WHERE code=?", Long.class, marker);
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder().cookieHandler(cookies).build();
    }
    @AfterEach void cleanup() {
        if (client != null) client.close();
        jdbc.update("DELETE FROM employee_invitations WHERE branch_id=?", branchId);
        jdbc.update("DELETE FROM branch_memberships WHERE employee_id IN (?,?)", employeeId, otherEmployeeId);
        jdbc.update("DELETE FROM employees WHERE id IN (?,?)", employeeId, otherEmployeeId);
        jdbc.update("DELETE FROM branches WHERE id=?", branchId);
    }

    @Test void pendingSessionStaysRestrictedAfterApprovalUntilExplicitRelogin() throws Exception {
        acceptedInvitation(employeeId, email);
        acceptedInvitation(otherEmployeeId, "other-" + email);
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,status) VALUES (?,?,'PENDING')", employeeId, branchId);
        var login = login();
        assertThat(login.statusCode()).isEqualTo(200);
        var limited = json.readTree(login.body());
        assertThat(limited.get("sessionMode").asString()).isEqualTo("APPROVAL_ONLY");
        assertThat(limited.get("branches").size()).isZero();
        assertThat(limited.at("/user/memberships").size()).isZero();
        assertThat(limited.at("/user/nickname").asString()).isEmpty();
        assertThat(get("/api/session-test/activity").statusCode()).isEqualTo(403);
        var approvals = json.readTree(get("/api/invitations/approval-status").body());
        assertThat(approvals.get("invitations").size()).isEqualTo(1);
        assertThat(approvals.at("/invitations/0/status").asString()).isEqualTo("승인 대기");
        assertThat(approvals.get("requiresLogin").asBoolean()).isFalse();
        String before = sessionId();
        jdbc.update("UPDATE employees SET status='ACTIVE' WHERE id=?", employeeId);
        jdbc.update("UPDATE branch_memberships SET status='ACTIVE' WHERE employee_id=?", employeeId);
        jdbc.update("UPDATE employee_invitations SET status='APPROVED',approved_by=?,approved_at=UTC_TIMESTAMP(6) WHERE accepted_employee_id=?",
                otherEmployeeId, employeeId);
        assertThat(json.readTree(get("/api/invitations/approval-status").body()).get("requiresLogin").asBoolean()).isTrue();
        var me = json.readTree(get("/api/me").body());
        assertThat(me.get("sessionMode").asString()).isEqualTo("APPROVAL_ONLY");
        assertThat(me.get("branches").size()).isZero();
        assertThat(get("/api/session-test/activity").statusCode()).isEqualTo(403);
        var relogin = login();
        assertThat(relogin.statusCode()).isEqualTo(200);
        assertThat(json.readTree(relogin.body()).get("sessionMode").asString()).isEqualTo("WORKSPACE");
        assertThat(sessionId()).isNotEqualTo(before);
        assertThat(sessions.findById(before)).isNull();
        assertThat(get("/api/session-test/activity").statusCode()).isEqualTo(200);
    }

    @Test void activeEmployeeKeepsWorkspaceWhileAnotherMembershipIsPending() throws Exception {
        activate();
        acceptedInvitation(employeeId, email);
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,status) VALUES (?,?,'PENDING')", employeeId, branchId);
        var response = login();
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("sessionMode").asString()).isEqualTo("WORKSPACE");
        assertThat(get("/api/session-test/activity").statusCode()).isEqualTo(200);
        assertThat(json.readTree(get("/api/invitations/approval-status").body()).get("requiresLogin").asBoolean()).isFalse();
    }

    @Test void automaticMeAndCsrfTrafficDoesNotExtendExactSixtyMinuteIdleLimit() throws Exception {
        activate(); login();
        String id = sessionId();
        clock.advance(3599);
        assertThat(get("/api/me").statusCode()).isEqualTo(200);
        assertThat(get("/api/auth/csrf").statusCode()).isEqualTo(200);
        clock.advance(1);
        assertThat(get("/api/me").statusCode()).isEqualTo(401);
        assertThat(sessions.findById(id)).isNull();
    }

    @Test void actualBusinessRequestsExtendIdleButCannotExtendTwelveHourMaximum() throws Exception {
        activate(); login();
        for (int hour = 0; hour < 12; hour++) {
            clock.advance(3599);
            assertThat(get("/api/session-test/activity").statusCode()).isEqualTo(200);
        }
        clock.advance(12);
        assertThat(get("/api/session-test/activity").statusCode()).isEqualTo(401);
    }

    @Test void streamReconnectAndDeniedRequestsDoNotCountAsActivity() throws Exception {
        activate(); login();
        clock.advance(3599);
        assertThat(get("/api/session-test/denied").statusCode()).isEqualTo(403);
        var stream = client.send(HttpRequest.newBuilder(uri("/api/session-test/activity"))
                .header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofString());
        // A reconnect cannot extend the idle clock even if the endpoint rejects its representation.
        assertThat(stream.statusCode()).isBetween(200, 499);
        clock.advance(1);
        assertThat(get("/api/me").statusCode()).isEqualTo(401);
    }

    @Test void jdbcRestoredSessionKeepsFixedModeAndBothTimestampBoundaries() throws Exception {
        login();
        String id = sessionId();
        Session first = sessions.findById(id);
        Session reloaded = sessions.findById(id);
        assertThat(reloaded).isNotSameAs(first);
        assertThat(reloaded.<String>getAttribute(SessionBoundaryPolicy.SESSION_MODE)).isEqualTo("APPROVAL_ONLY");
        assertThat(reloaded.<Long>getAttribute(SessionBoundaryPolicy.AUTHENTICATED_AT))
                .isEqualTo(first.<Long>getAttribute(SessionBoundaryPolicy.AUTHENTICATED_AT));
        assertThat(reloaded.<Long>getAttribute(SessionBoundaryPolicy.LAST_ACTIVITY_AT))
                .isEqualTo(first.<Long>getAttribute(SessionBoundaryPolicy.LAST_ACTIVITY_AT));
        clock.advance(3600);
        assertThat(get("/api/invitations/approval-status").statusCode()).isEqualTo(401);
    }

    @Test void jdbcAuthenticationSurvivesClosingAndRestartingApplicationInstance() throws Exception {
        activate();
        int originalPort = port;
        String id;
        try {
            try (var first = startAnotherApplicationInstance()) {
                port = ((org.springframework.boot.web.server.context.WebServerApplicationContext) first).getWebServer().getPort();
                assertThat(login().statusCode()).isEqualTo(200);
                id = sessionId();
            }
            try (var restarted = startAnotherApplicationInstance()) {
                port = ((org.springframework.boot.web.server.context.WebServerApplicationContext) restarted).getWebServer().getPort();
                var me = get("/api/me");
                assertThat(me.statusCode()).isEqualTo(200);
                assertThat(json.readTree(me.body()).at("/user/id").asString()).isEqualTo(Long.toString(employeeId));
                assertThat(json.readTree(me.body()).get("sessionMode").asString()).isEqualTo("WORKSPACE");
                assertThat(sessionId()).isEqualTo(id);
            }
        } finally { port = originalPort; }
    }

    org.springframework.context.ConfigurableApplicationContext startAnotherApplicationInstance() {
        // Every JDBC/Flyway property is the running test container, never process/local DB defaults.
        return new org.springframework.boot.builder.SpringApplicationBuilder(com.openstudy.OpenstudyBackendApplication.class)
                .profiles("test").run("--server.port=0", "--spring.config.import=optional:classpath:bootstrap-session-test.properties",
                        "--spring.datasource.url=" + mysql.getJdbcUrl(), "--spring.datasource.username=" + mysql.getUsername(),
                        "--spring.datasource.password=" + mysql.getPassword(), "--spring.flyway.url=" + mysql.getJdbcUrl(),
                        "--spring.flyway.user=" + mysql.getUsername(), "--spring.flyway.password=" + mysql.getPassword(),
                        "--spring.session.jdbc.cleanup-cron=-");
    }

    @Test void pendingLogoutClearsCookieAndCurrentAccountRestrictionDestroysSession() throws Exception {
        var response = login();
        assertThat(response.headers().allValues("Set-Cookie").toString()).contains("HttpOnly", "SameSite=Lax");
        String id = sessionId();
        var logout = post("/api/auth/logout", csrf(), Map.of());
        assertThat(logout.statusCode()).isEqualTo(200);
        assertThat(logout.headers().allValues("Set-Cookie").toString()).contains("Max-Age=0");
        assertThat(sessions.findById(id)).isNull();
        login(); id = sessionId();
        jdbc.update("UPDATE employees SET status='LEFT' WHERE id=?", employeeId);
        assertThat(get("/api/invitations/approval-status").statusCode()).isEqualTo(401);
        assertThat(sessions.findById(id)).isNull();
    }

    void activate() { jdbc.update("UPDATE employees SET status='ACTIVE' WHERE id=?", employeeId); }
    void acceptedInvitation(long acceptedId, String inviteeEmail) {
        jdbc.update("INSERT INTO employee_invitations(branch_id,invitee_email,invitee_name,created_by,status,expires_at,accepted_employee_id,accepted_at) "
                + "VALUES (?,?,'초대 직원',?,'ACCEPTED',UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6))", branchId, inviteeEmail, otherEmployeeId, acceptedId);
    }
    HttpResponse<String> login() throws Exception { return post("/api/auth/login", csrf(), Map.of("email", email, "password", PASSWORD)); }
    String csrf() throws Exception { return json.readTree(get("/api/auth/csrf").body()).get("token").asString(); }
    String sessionId() {
        String value = cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("SESSION"))
                .findFirst().orElseThrow().getValue().replace("\"", "");
        return new String(Base64.getDecoder().decode(value), java.nio.charset.StandardCharsets.UTF_8);
    }
    URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String path, String csrf, Map<String, String> body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .header("X-CSRF-Token", csrf).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
