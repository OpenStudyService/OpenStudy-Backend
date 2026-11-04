package com.openstudy.integration.http;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

@MySqlIntegrationTest
@ActiveProfiles({"test", "local"})
@TestPropertySource(properties = {"BOOTSTRAP_OWNER_EMAIL=", "BOOTSTRAP_OWNER_PASSWORD=", "openstudy.local-preview.enabled=true"})
class LocalPreviewIntegrationTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired MySQLContainer mysql;
    private HttpClient client;
    private long employeeId;

    @BeforeEach
    void authenticateInDedicatedTestDatabase() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("openstudy_test");
            assertThat(connection.getMetaData().getURL().split("\\?")[0]).isEqualTo(mysql.getJdbcUrl().split("\\?")[0]);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM branches", Integer.class)).isEqualTo(2);
        String email = UUID.randomUUID() + "@example.com";
        String password = "preview-test-password";
        jdbc.update("INSERT INTO employees (email,password_hash,name,nickname,global_role,status) "
                + "VALUES (?,?,'테스트 사장님','사장님','OWNER','ACTIVE')", email, passwords.encode(password));
        employeeId = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(get("/api/auth/csrf").body()).get("token").asString();
        var login = request("/api/auth/login").header("X-CSRF-Token", csrf).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("email", email, "password", password))));
        assertThat(client.send(login.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    @AfterEach
    void cleanOnlyFixture() {
        if (client != null) client.close();
        jdbc.update("DELETE FROM branch_memberships WHERE employee_id=?", employeeId);
        jdbc.update("DELETE FROM employees WHERE id=?", employeeId);
    }

    @Test
    void previewKeepsExistingUiSortOptionsButRejectsPrivateFields() throws Exception {
        // /api/tasks (#75), /api/seats (#61), /api/facilities (#78), /api/rooms (#69), /api/lockers (#71), /api/shifts (#83)
        // and attendance (#84, /api/attendance-records)
        // are real operational endpoints now.
        // /api/products is a real operational endpoint now (#16); members is still preview-only.
        assertThat(get("/api/members?sort=status").statusCode()).isEqualTo(200);
        assertThat(get("/api/members?sort=password_hash").statusCode()).isEqualTo(422);
        assertThat(get("/api/members?sort=due").statusCode()).isEqualTo(422);
    }

    @Test
    void homeAndLookupsContainConsistentSampleDataAfterRealLogin() throws Exception {
        var me = json.readTree(get("/api/me").body());
        assertThat(me.get("branches").size()).isEqualTo(2);
        var lookups = get("/api/local-preview/lookups?scope=all");
        assertThat(lookups.statusCode()).isEqualTo(200);
        var lookupData = json.readTree(lookups.body());
        assertThat(lookupData.get("preview").asBoolean()).isTrue();
        assertThat(lookupData.get("branches").size()).isEqualTo(2);
        assertThat(lookupData.get("employees").size()).isEqualTo(3);
        assertThat(lookupData.get("seats").size()).isEqualTo(24);
        var homeResponse = get("/api/home?scope=all");
        assertThat(homeResponse.statusCode()).isEqualTo(200);
        var home = json.readTree(homeResponse.body());
        assertThat(home.get("tasks").size()).isEqualTo(6);
        assertThat(home.get("seats").size()).isEqualTo(24);
        assertThat(home.get("notices").size()).isEqualTo(2);
        assertThat(home.get("shifts").size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_tasks", Integer.class)).isZero();
        try (var anonymous = HttpClient.newHttpClient()) {
            assertThat(anonymous.send(request("/api/lookups").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void scopesPagesAndDetailsCannotAccessUnauthorizedBranches() throws Exception {
        String branch = json.readTree(get("/api/me").body()).at("/branches/0/id").asString();
        var scoped = json.readTree(get("/api/home?scope=" + branch).body());
        assertThat(scoped.get("seats").size()).isEqualTo(12);
        for (var seat : scoped.get("seats")) assertThat(seat.get("branchId").asString()).isEqualTo(branch);
        assertThat(get("/api/home?scope=999999999").statusCode()).isEqualTo(403);
        var page = json.readTree(get("/api/members?scope=" + branch + "&page=1&pageSize=5").body());
        assertThat(page.at("/meta/total").asInt()).isEqualTo(1);
        assertThat(page.get("items").size()).isEqualTo(1);
        String id = page.at("/items/0/id").asString();
        assertThat(get("/api/members/" + id + "?scope=" + branch).statusCode()).isEqualTo(200);
        String otherBranch = json.readTree(get("/api/me").body()).at("/branches/1/id").asString();
        assertThat(get("/api/members/" + id + "?scope=" + otherBranch).statusCode()).isEqualTo(404);
        assertThat(get("/api/members?page=0").statusCode()).isEqualTo(422);
    }

    @Test
    void ordinaryStaffCanOpenTheirBranchWithoutPeopleManagementPermission() throws Exception {
        var branches = json.readTree(get("/api/me").body()).get("branches");
        String branch = branches.get(0).get("id").asString();
        String other = branches.get(1).get("id").asString();
        jdbc.update("UPDATE employees SET global_role='EMPLOYEE' WHERE id=?", employeeId);
        jdbc.update("INSERT INTO branch_memberships (employee_id,branch_id,role,status) VALUES (?,?,'STAFF','ACTIVE')",
                employeeId, Long.parseLong(branch));
        var home = get("/api/home?scope=" + branch);
        assertThat(home.statusCode()).isEqualTo(200);
        var homeData = json.readTree(home.body());
        assertThat(homeData.get("seats").size()).isEqualTo(12);
        for (var seat : homeData.get("seats")) assertThat(seat.get("branchId").asString()).isEqualTo(branch);
        var lookups = get("/api/local-preview/lookups?scope=" + branch);
        assertThat(lookups.statusCode()).isEqualTo(200);
        assertThat(json.readTree(lookups.body()).get("employees").size()).isZero();
        assertThat(get("/api/home?scope=" + other).statusCode()).isEqualTo(403);
        assertThat(get("/api/local-preview/lookups?scope=" + other).statusCode()).isEqualTo(403);
    }

    @Test
    void rejectsSampleWritesAndDoesNotChangeDatabase() throws Exception {
        String csrf = json.readTree(get("/api/auth/csrf").body()).get("token").asString();
        var response = client.send(request("/api/members").header("X-CSRF-Token", csrf)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(501);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("PREVIEW_READ_ONLY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_tasks", Integer.class)).isZero();
    }

    @Test
    void shiftsAreAnsweredByTheOperationalApiEvenInThePreview() throws Exception {
        // #83 replaced the sample /api/shifts list, detail and 501 writes; the home and lookup samples keep their shift rows.
        var page = get("/api/shifts?page=1&pageSize=5");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).doesNotContain("preview-shift");
        assertThat(json.readTree(page.body()).has("items")).isTrue();
        assertThat(get("/api/shifts/preview-shift-1").statusCode()).isEqualTo(422);
        assertThat(get("/api/shifts?sort=password_hash").statusCode()).as("no sort parameter at all").isEqualTo(200);
        String csrf = json.readTree(get("/api/auth/csrf").body()).get("token").asString();
        var write = client.send(request("/api/shifts").header("X-CSRF-Token", csrf)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(write.statusCode()).isEqualTo(422);
        assertThat(json.readTree(write.body()).get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(get("/api/shifts/weekly").statusCode()).isEqualTo(200);
    }

    @Test void attendanceIsAnsweredByTheOperationalApiAndTheSampleListIsGone() throws Exception {
        // #84 retired the sample /api/attendance list; the operational paths never overlapped it. The lookup sample keeps its key.
        assertThat(get("/api/attendance").statusCode()).isEqualTo(404);
        assertThat(get("/api/attendance?page=1&pageSize=5").statusCode()).isEqualTo(404);
        var page = get("/api/attendance-records?page=1&pageSize=5");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(json.readTree(page.body()).has("items")).isTrue();
        var current = get("/api/me/attendance/current");
        assertThat(current.statusCode()).isEqualTo(200);
        assertThat(json.readTree(current.body()).get("attendance").isNull()).isTrue();
        // The preview OWNER has no membership row, so the real clock-in refuses it (M8-D08) and writes nothing.
        String csrf = json.readTree(get("/api/auth/csrf").body()).get("token").asString();
        String branch = json.readTree(get("/api/me").body()).at("/branches/0/id").asString();
        var clockIn = client.send(request("/api/me/attendance/clock-in").header("X-CSRF-Token", csrf)
                .header("Idempotency-Key", UUID.randomUUID().toString()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("branchId", branch)))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(clockIn.statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_records WHERE employee_id=?", Integer.class, employeeId)).isZero();
        assertThat(json.readTree(get("/api/local-preview/lookups?scope=all").body()).has("attendance")).isTrue();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
