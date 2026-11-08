package com.openstudy.modules.ai;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.modules.ai.service.AiReadiness;
import com.openstudy.modules.ai.service.OpenStudyAnalyses;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

/**
 * #93: while the catalog is not ready no question runs, saved questions show the reason instead of data, and the
 * readiness check can be read again to see why.
 */
@MySqlIntegrationTest
class AiNotReadyIntegrationTests {
    static final String PASSWORD = "ai-not-ready-password";

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @MockitoSpyBean AiReadiness readiness;
    @LocalServerPort int port;

    long owner;
    String conversation, execution;

    @BeforeEach void setup() {
        String email = UUID.randomUUID() + "@ai-not-ready.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,'AI 직원','AI','OWNER','ACTIVE')",
                email, passwords.encode(PASSWORD));
        owner = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        conversation = "conv-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO ai_conversations(id,employee_id,scope_key,title) VALUES(?,?,'all','준비 확인')", conversation, owner);
        execution = "exec-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO ai_executions(id,request_ref,conversation_id,employee_id,scope_key,branch_ids_csv,question,"
                        + "analysis_id,intent_domain,status,as_of_utc,snapshot_version,policy_version) "
                        + "VALUES(?,?,?,?,'all','','만료 예정 이용권 알려줘',?,'passes','READY','2026-11-01 00:00:00','s1','p1')",
                execution, "req-" + execution, conversation, owner, OpenStudyAnalyses.ID_POC_006);
        doReturn(new AiReadiness.Status(false, List.of("지표 sales.net_sales_krw가 등록 분석과 연결되지 않았습니다."), List.of()))
                .when(readiness).status();
    }

    @AfterEach void clean() {
        reset(readiness);
        jdbc.update("DELETE FROM ai_executions WHERE employee_id=?", owner);
        jdbc.update("DELETE FROM ai_conversations WHERE employee_id=?", owner);
        jdbc.update("DELETE FROM ai_traces WHERE employee_id=?", owner);
        jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", Long.toString(owner));
        jdbc.update("DELETE FROM audit_events WHERE actor_employee_id=?", owner);
        jdbc.update("DELETE FROM employees WHERE id=?", owner);
    }

    @Test void nothingRunsWhileTheCatalogIsNotReady() throws Exception {
        try (var s = login()) {
            JsonNode status = json.readTree(send(s, "GET", "/api/ai/catalog/readiness", null).body());
            assertThat(status.get("ready").asBoolean()).isFalse();
            assertThat(status.get("reasons").toString()).contains("sales.net_sales_krw");

            var asked = send(s, "POST", "/api/ai/queries", Map.of("question", "만료 예정 이용권 알려줘", "scope", "all"));
            assertThat(asked.statusCode()).as(asked.body()).isEqualTo(503);
            JsonNode refused = json.readTree(asked.body());
            assertThat(refused.get("status").asString()).isEqualTo("NOT_READY");
            assertThat(refused.get("errorCode").asString()).isEqualTo("AI_NOT_READY");
            assertThat(refused.get("envelope").isNull()).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_executions WHERE employee_id=?", Long.class, owner)).isEqualTo(1);

            JsonNode turn = json.readTree(send(s, "GET", "/api/ai/conversations/" + conversation, null).body())
                    .get("turns").get(0).get("response");
            assertThat(turn.get("status").asString()).isEqualTo("REFUSED");
            assertThat(turn.get("errorCode").asString()).isEqualTo("AI_NOT_READY");
            assertThat(turn.get("envelope").isNull()).isTrue();

            var rerun = send(s, "GET", "/api/ai/executions/" + execution, null);
            assertThat(rerun.statusCode()).as(rerun.body()).isEqualTo(503);
            assertThat(json.readTree(rerun.body()).get("code").asString()).isEqualTo("AI_NOT_READY");
        }
    }

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login() throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(raw(client, "GET", "/api/auth/csrf", null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, owner);
        assertThat(raw(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", PASSWORD)).statusCode())
                .isEqualTo(200);
        return new Session(client, json.readTree(raw(client, "GET", "/api/auth/csrf", null, null).body()).get("token").asString());
    }

    HttpResponse<String> raw(HttpClient client, String method, String path, String csrf, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (csrf != null) request.header("X-CSRF-Token", csrf);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> send(Session s, String method, String path, Object body) throws Exception {
        return raw(s.client(), method, path, "GET".equals(method) ? null : s.token(), body);
    }
}
