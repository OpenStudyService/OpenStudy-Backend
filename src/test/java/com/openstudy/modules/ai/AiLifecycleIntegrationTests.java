package com.openstudy.modules.ai;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.domainlens.catalog.SemanticRegistry;
import com.openstudy.modules.ai.service.AiTraceRetention;
import com.openstudy.modules.ai.service.OpenStudyAnalyses;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #93 and #96 (ADR-M10-003): the catalog readiness is checked for real, a diagnostic trace keeps the question text for
 * 30 days only, and a question resubmitted with the same client key makes no new execution.
 */
@MySqlIntegrationTest
class AiLifecycleIntegrationTests {
    static final String PASSWORD = "ai-lifecycle-password";

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @Autowired SemanticRegistry registry;
    @Autowired AiTraceRetention retention;
    @LocalServerPort int port;

    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>();
    long owner, staff, branch;

    @BeforeEach void setup() {
        owner = person("OWNER");
        staff = person("EMPLOYEE");
        var hours = IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        branch = Long.parseLong(branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create("수명주기 지점", "서울", "02-123", hours, "운영 중"), "ai-lifecycle").id());
        branches.add(branch);
        member(staff, branch, "ai:read");
    }

    @AfterEach void clean() {
        String actors = people.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.update("DELETE FROM ai_executions WHERE employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM ai_conversations WHERE employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM ai_traces WHERE employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM audit_events WHERE actor_employee_id IN (" + actors + ")");
        for (long id : branches) {
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branches WHERE id=?", id);
        }
        jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM employees WHERE id IN (" + actors + ")");
    }

    @Test void theRealCatalogIsReadyAndEveryLinkIsChecked() throws Exception {
        try (var s = login(staff)) {
            JsonNode readiness = ok(s, "/api/ai/catalog/readiness");
            assertThat(readiness.get("ready").asBoolean()).as(readiness.toString()).isTrue();
            assertThat(readiness.get("reasons")).isEmpty();
        }
        // A metric whose analysis is no longer registered, or an analysis without a metric, makes the catalog not ready.
        var broken = com.openstudy.modules.ai.service.AiReadiness.problems(registry,
                id -> !id.equals(OpenStudyAnalyses.ID_POC_001), OpenStudyAnalyses.REGISTERED);
        assertThat(broken).anyMatch(reason -> reason.contains("sales.net_sales_krw"))
                .anyMatch(reason -> reason.contains(OpenStudyAnalyses.ID_POC_001));
        var extra = com.openstudy.modules.ai.service.AiReadiness.problems(registry, id -> true,
                Set.of(OpenStudyAnalyses.ID_POC_001, "openstudy.unlinked.analysis"));
        assertThat(extra).containsExactly("등록 분석 openstudy.unlinked.analysis에 연결된 지표가 없습니다.");
    }

    @Test void aTraceKeepsTheQuestionTextForThirtyDaysOnly() {
        Instant now = Instant.now();
        String expired = trace(staff, "지난달 질문 원문", now.minus(Duration.ofDays(1)));
        String fresh = trace(staff, "어제 질문 원문", now.plus(Duration.ofDays(29)));
        assertThat(retention.clearExpiredQuestions()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sanitized_question FROM ai_traces WHERE id=?", String.class, expired)).isNull();
        assertThat(jdbc.queryForObject("SELECT sanitized_question FROM ai_traces WHERE id=?", String.class, fresh)).isEqualTo("어제 질문 원문");
        // Only the text goes: the rest of the trace stays.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_traces WHERE id=? AND status='READY'", Long.class, expired)).isEqualTo(1);
    }

    @Test void aResubmissionWithTheSameClientKeyMakesNoNewExecution() throws Exception {
        String conversation = "conv-" + UUID.randomUUID().toString().substring(0, 12);
        try (var s = login(staff)) {
            var first = ask(s, conversation, "client-key-1");
            assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
            JsonNode original = json.readTree(first.body());
            assertThat(original.get("status").asString()).isEqualTo("COMPLETED");

            var again = ask(s, conversation, "client-key-1");
            assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
            JsonNode replayed = json.readTree(again.body());
            assertThat(replayed.get("executionId").asString()).isEqualTo(original.get("executionId").asString());
            assertThat(replayed.get("behavior").asString()).isEqualTo("REPLAY");
            assertThat(replayed.get("status").asString()).isEqualTo("COMPLETED");
            assertThat(count("SELECT COUNT(*) FROM ai_executions WHERE employee_id=?", staff)).isEqualTo(1);

            var other = ask(s, conversation, "client-key-2");
            assertThat(other.statusCode()).isEqualTo(200);
            assertThat(count("SELECT COUNT(*) FROM ai_executions WHERE employee_id=?", staff)).isEqualTo(2);

            assertThat(ask(s, conversation, "bad key!").statusCode()).isEqualTo(422);
        }
        // The key is the employee's own: another employee's same key is a new question.
        member(owner, branch);
        try (var s = login(owner)) {
            var mine = ask(s, null, "client-key-1");
            assertThat(mine.statusCode()).as(mine.body()).isEqualTo(200);
            assertThat(count("SELECT COUNT(*) FROM ai_executions WHERE employee_id=?", owner)).isEqualTo(1);
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------------------

    String trace(long employee, String question, Instant expiresAt) {
        String id = "trc-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO ai_traces(id,request_ref,employee_id,scope_key,sanitized_question,status,stages_json,"
                + "retrieval_json,security_gate_json,question_expires_at) VALUES(?,?,?,'all',?,'READY','[]','[]','{}',?)",
                id, "req-" + id, employee, question, utc(expiresAt));
        return id;
    }

    static String utc(Instant at) { return at.atOffset(ZoneOffset.UTC).toLocalDateTime().toString().replace('T', ' '); }

    long count(String sql, Object... values) { return jdbc.queryForObject(sql, Long.class, values); }

    long person(String role) {
        String email = UUID.randomUUID() + "@ai-lifecycle.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES(?,?,'AI 직원','AI',?,'ACTIVE')",
                email, passwords.encode(PASSWORD), role);
        long id = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        people.add(id);
        return id;
    }

    void member(long person, long branch, String... grants) {
        jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status) VALUES(?,?,'STAFF','ACTIVE')", person, branch);
        for (String grant : grants)
            jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT m.id,p.id "
                    + "FROM branch_memberships m JOIN permission_definitions p ON p.code=? WHERE m.employee_id=? AND m.branch_id=?",
                    grant, person, branch);
    }

    // ---- HTTP -----------------------------------------------------------------------------------------------

    record Session(HttpClient client, String token) implements AutoCloseable { public void close() { client.close(); } }

    Session login(long person) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf = json.readTree(raw(client, "GET", "/api/auth/csrf", null, null).body()).get("token").asString();
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, person);
        assertThat(raw(client, "POST", "/api/auth/login", csrf, Map.of("email", email, "password", PASSWORD)).statusCode())
                .isEqualTo(200);
        return new Session(client, json.readTree(raw(client, "GET", "/api/auth/csrf", null, null).body()).get("token").asString());
    }

    HttpResponse<String> ask(Session s, String conversation, String key) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("question", "만료 예정 이용권 알려줘");
        body.put("scope", Long.toString(branch));
        body.put("clientRequestKey", key);
        if (conversation != null) body.put("conversationId", conversation);
        return send(s, "POST", "/api/ai/queries", body);
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

    JsonNode ok(Session s, String path) throws Exception {
        var response = send(s, "GET", path, null);
        assertThat(response.statusCode()).as("%s %s", path, response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
}
