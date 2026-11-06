package com.openstudy.modules.ai;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

import com.openstudy.modules.ai.service.OpenStudyAnalyses;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Saved AI conversations (#168, ADR-M10-002 M10-D12): the list is the employee's own, reopening re-runs the saved
 * analysis under the current permissions and a new as-of time, a question that cannot run any more shows its reason
 * instead of data, and another employee's conversation is a plain 404 for every operation.
 */
@MySqlIntegrationTest
class AiConversationHistoryIntegrationTests {
    static final String PASSWORD = "ai-history-password";

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    @LocalServerPort int port;

    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>();
    final List<String> conversations = new ArrayList<>();
    long owner, analyst, stranger, branch;

    @BeforeEach void setup() {
        owner = person("OWNER");
        analyst = person("EMPLOYEE");
        stranger = person("EMPLOYEE");
        var hours = IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        branch = Long.parseLong(branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create("AI 기록 지점", "서울", "02-123", hours, "운영 중"), "ai-history").id());
        branches.add(branch);
        // ai:read without sales:read: a saved sales question can no longer run for this employee.
        member(analyst, branch, "ai:read");
        member(stranger, branch, "ai:read", "sales:read");
    }

    @AfterEach void clean() {
        for (String id : conversations) {
            jdbc.update("DELETE FROM ai_executions WHERE conversation_id=?", id);
            jdbc.update("DELETE FROM ai_conversations WHERE id=?", id);
        }
        String actors = people.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.update("DELETE FROM ai_traces WHERE employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM audit_events WHERE actor_employee_id IN (" + actors + ")");
        for (long id : branches) {
            jdbc.update("DELETE FROM workspace_event_outbox WHERE branch_id=?", id);
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branches WHERE id=?", id);
        }
        jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM employees WHERE id IN (" + actors + ")");
    }

    @Test void theListIsTheEmployeesOwnAndReopeningRerunsSavedAnalysesWithoutData() throws Exception {
        String conversation = conversation(owner, "매출 점검");
        execution(conversation, owner, "지점별 매출을 비교해줘", "READY", OpenStudyAnalyses.ID_POC_001, "sales", "2026-11-01 01:00:00");
        execution(conversation, owner, "매출", "CLARIFICATION", null, "sales", "2026-11-01 01:01:00");
        conversation(stranger, "남의 대화");

        try (var s = login(owner)) {
            JsonNode list = ok(s, "/api/ai/conversations");
            assertThat(list).hasSize(1);
            assertThat(list.get(0).get("id").asString()).isEqualTo(conversation);
            assertThat(list.get(0).get("title").asString()).isEqualTo("매출 점검");

            JsonNode reopened = ok(s, "/api/ai/conversations/" + conversation + "?scope=all");
            JsonNode turns = reopened.get("turns");
            assertThat(turns).hasSize(2);
            JsonNode ran = turns.get(0).get("response");
            assertThat(turns.get(0).get("question").asString()).isEqualTo("지점별 매출을 비교해줘");
            assertThat(ran.get("status").asString()).isEqualTo("COMPLETED");
            assertThat(ran.get("resultCategory").asString()).isEqualTo("REOPENED");
            assertThat(ran.get("envelope").get("asOf").asString()).isNotBlank();
            assertThat(ran.get("envelope").get("datasets").isArray()).isTrue();
            JsonNode asked = turns.get(1).get("response");
            assertThat(asked.get("status").asString()).isEqualTo("CLARIFICATION");
            assertThat(asked.get("envelope").isNull()).isTrue();
        }
    }

    @Test void aSavedQuestionTheEmployeeMayNoLongerRunShowsTheReasonInsteadOfData() throws Exception {
        String conversation = conversation(analyst, "권한 회수 뒤");
        execution(conversation, analyst, "지점별 매출을 비교해줘", "READY", OpenStudyAnalyses.ID_POC_001, "sales", "2026-11-01 01:00:00");
        try (var s = login(analyst)) {
            JsonNode response = ok(s, "/api/ai/conversations/" + conversation + "?scope=" + branch).get("turns").get(0).get("response");
            assertThat(response.get("status").asString()).isEqualTo("REFUSED");
            assertThat(response.get("message").asString()).startsWith("지금 권한이나 지점 범위로는 다시 조회할 수 없어요.");
            assertThat(response.get("envelope").isNull()).isTrue();
        }
    }

    @Test void renameAndDeleteAreTheOwnersAndAnotherEmployeeGetsA404() throws Exception {
        String conversation = conversation(owner, "처음 제목");
        execution(conversation, owner, "지점별 매출을 비교해줘", "READY", OpenStudyAnalyses.ID_POC_001, "sales", "2026-11-01 01:00:00");
        try (var other = login(stranger)) {
            assertThat(send(other, "GET", "/api/ai/conversations/" + conversation, null).statusCode()).isEqualTo(404);
            assertThat(send(other, "PATCH", "/api/ai/conversations/" + conversation, Map.of("title", "가로채기")).statusCode()).isEqualTo(404);
            assertThat(send(other, "DELETE", "/api/ai/conversations/" + conversation, null).statusCode()).isEqualTo(404);
            var appended = send(other, "POST", "/api/ai/queries",
                    Map.of("question", "지점별 매출을 비교해줘", "scope", "all", "conversationId", conversation));
            assertThat(appended.statusCode()).isEqualTo(404);
        }
        try (var s = login(owner)) {
            assertThat(send(s, "PATCH", "/api/ai/conversations/" + conversation, Map.of("title", " ")).statusCode()).isEqualTo(422);
            var renamed = send(s, "PATCH", "/api/ai/conversations/" + conversation, Map.of("title", "새 제목"));
            assertThat(renamed.statusCode()).isEqualTo(200);
            assertThat(ok(s, "/api/ai/conversations").get(0).get("title").asString()).isEqualTo("새 제목");
            assertThat(send(s, "DELETE", "/api/ai/conversations/" + conversation, null).statusCode()).isEqualTo(204);
            assertThat(ok(s, "/api/ai/conversations")).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_executions WHERE conversation_id=?", Long.class, conversation))
                    .isZero();
        }
    }

    // ---- fixtures -------------------------------------------------------------------------------------------

    long person(String role) {
        String email = UUID.randomUUID() + "@ai-history.test";
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

    String conversation(long employee, String title) {
        String id = "conv-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO ai_conversations(id,employee_id,scope_key,title) VALUES(?,?,'all',?)", id, employee, title);
        conversations.add(id);
        return id;
    }

    void execution(String conversation, long employee, String question, String status, String analysisId, String domain,
            String createdAt) {
        String id = "exec-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO ai_executions(id,request_ref,conversation_id,employee_id,scope_key,branch_ids_csv,question,"
                        + "analysis_id,intent_domain,status,as_of_utc,snapshot_version,policy_version,created_at) "
                        + "VALUES(?,?,?,?,'all','',?,?,?,?,'2026-11-01 00:00:00','s1','p1',?)",
                id, "req-" + id, conversation, employee, question, analysisId, domain, status, createdAt);
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
