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
@TestPropertySource(properties = {"BOOTSTRAP_OWNER_EMAIL=", "BOOTSTRAP_OWNER_PASSWORD="})
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
        jdbc.update("DELETE FROM employees WHERE id=?", employeeId);
    }

    @Test
    void previewKeepsExistingUiSortOptionsButRejectsPrivateFields() throws Exception {
        assertThat(get("/api/tasks?sort=status").statusCode()).isEqualTo(200);
        assertThat(get("/api/tasks?sort=due").statusCode()).isEqualTo(200);
        // /api/products is a real operational endpoint now (#16); members is still preview-only.
        assertThat(get("/api/members?sort=status").statusCode()).isEqualTo(200);
        assertThat(get("/api/tasks?sort=password_hash").statusCode()).isEqualTo(422);
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
        var page = json.readTree(get("/api/tasks?scope=" + branch + "&page=2&pageSize=2").body());
        assertThat(page.at("/meta/total").asInt()).isEqualTo(3);
        assertThat(page.get("items").size()).isEqualTo(1);
        String id = page.at("/items/0/id").asString();
        assertThat(get("/api/tasks/" + id + "?scope=" + branch).statusCode()).isEqualTo(200);
        String otherBranch = json.readTree(get("/api/me").body()).at("/branches/1/id").asString();
        assertThat(get("/api/tasks/" + id + "?scope=" + otherBranch).statusCode()).isEqualTo(404);
        assertThat(get("/api/tasks?page=0").statusCode()).isEqualTo(422);
    }

    @Test
    void rejectsSampleWritesAndDoesNotChangeDatabase() throws Exception {
        String csrf = json.readTree(get("/api/auth/csrf").body()).get("token").asString();
        var response = client.send(request("/api/tasks").header("X-CSRF-Token", csrf)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(501);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("PREVIEW_READ_ONLY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM work_tasks", Integer.class)).isZero();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
