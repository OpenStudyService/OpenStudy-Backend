package com.openstudy.modules.workspace;

import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.modules.workspace.service.WorkspaceEventService;
import com.openstudy.support.database.MySqlIntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@MySqlIntegrationTest
@TestPropertySource(properties = {
        "openstudy.workspace.scheduling.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=1000"
})
class WorkspaceConnectionIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired WorkspaceEventService events;
    @LocalServerPort int port;

    @Test
    void openStreamsReleaseDatabaseConnectionsBeforeTheBrowserDisconnects() throws Exception {
        String email = UUID.randomUUID() + "@stream-pool.test", password = "stream-pool-password";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) "
                + "VALUES (?,?,'연결 검사','연결 검사','OWNER','ACTIVE')", email, passwords.encode(password));
        long actor = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        var bodies = new ArrayList<InputStream>();
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            try {
                String csrf = json.readTree(client.send(request("/api/auth/csrf").GET().build(), HttpResponse.BodyHandlers.ofString()).body())
                        .get("token").asString();
                var login = client.send(request("/api/auth/login").header("X-CSRF-Token", csrf).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("email", email, "password", password)))).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(login.statusCode()).isEqualTo(200);
                // Three live streams exceed the two-connection pool without occupying it.
                for (int count = 0; count < 3; count++) {
                    var stream = client.send(request("/api/workspace/events?scope=all").header("Accept", "text/event-stream")
                            .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                    bodies.add(stream.body());
                    assertThat(stream.statusCode()).isEqualTo(200);
                    assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                        while (((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections() != 0) Thread.sleep(25);
                    });
                }
                assertThat(client.send(request("/api/me").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            } finally {
                bodies.forEach(body -> { try { body.close(); } catch (java.io.IOException ignored) {} });
                client.shutdownNow();
            }
        } finally {
            bodies.forEach(body -> { try { body.close(); } catch (java.io.IOException ignored) {} });
            jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", Long.toString(actor));
            events.tick();
            jdbc.update("DELETE FROM employees WHERE id=?", actor);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5));
    }
}
