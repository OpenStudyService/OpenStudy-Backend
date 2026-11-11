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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.modules.ai.service.OpenStudyAnalyses;
import com.openstudy.modules.ai.service.OpenStudyPlaybackGateway;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.dto.BranchRequests;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
    /** Every model call goes through planQuestion; counting it shows that reopening never asks the model (M10-D12). */
    @MockitoSpyBean OpenStudyPlaybackGateway gateway;
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
        jdbc.update("DELETE FROM ai_work_records WHERE employee_id IN ("
                + people.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")");
        for (String id : conversations) {
            jdbc.update("DELETE FROM ai_executions WHERE conversation_id=?", id);
            jdbc.update("DELETE FROM ai_conversations WHERE id=?", id);
        }
        String actors = people.stream().map(String::valueOf).collect(Collectors.joining(","));
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

    @Test void noResultIsStoredAndEveryResultIsRunAgainUnderTheCurrentPermissions() throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                + "AND ((table_name='ai_executions' AND column_name IN ('envelope_json','legacy_execution_json')) "
                + "OR (table_name='ai_work_records' AND column_name='snapshot_json'))", Long.class)).isZero();

        String conversation = conversation(owner, "결과 미저장");
        String ran = execution(conversation, owner, "지점별 매출을 비교해줘", "READY", OpenStudyAnalyses.ID_POC_001, "sales",
                "2026-11-01 01:00:00", Long.toString(branch));
        try (var s = login(owner)) {
            JsonNode execution = ok(s, "/api/ai/executions/" + ran);
            assertThat(execution.get("envelope").get("datasets").isArray()).isTrue();

            var created = send(s, "POST", "/api/ai/records", Map.of("executionId", ran, "title", "매출 기록"));
            assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
            String record = json.readTree(created.body()).get("id").asString();
            JsonNode opened = ok(s, "/api/ai/records/" + record);
            assertThat(opened.get("snapshot").get("datasets").isArray()).isTrue();

            assertThat(send(s, "DELETE", "/api/ai/conversations/" + conversation, null).statusCode()).isEqualTo(204);
            JsonNode orphan = ok(s, "/api/ai/records/" + record);
            assertThat(orphan.get("snapshot").isNull()).isTrue();
            assertThat(orphan.get("snapshotUnavailableReason").asString()).contains("삭제");
        }
        String other = conversation(analyst, "권한 없는 실행");
        String denied = execution(other, analyst, "지점별 매출을 비교해줘", "READY", OpenStudyAnalyses.ID_POC_001, "sales",
                "2026-11-01 01:00:00", Long.toString(branch));
        try (var s = login(analyst)) {
            assertThat(send(s, "GET", "/api/ai/executions/" + denied, null).statusCode()).isEqualTo(403);
        }
    }

    @Test void reopeningKeepsTheConditionsTheQuestionAskedFor() throws Exception {
        String conversation = conversation(owner, "조건 보존");
        String original = execution(conversation, owner, "원결제일 기준으로 항목별 매출", "READY", OpenStudyAnalyses.ID_POC_008,
                "sales", "2026-11-01 01:00:00", Long.toString(branch));
        jdbc.update("UPDATE ai_executions SET refund_basis='ORIGINAL_PAYMENT_DATE' WHERE id=?", original);
        String zeroFill = execution(conversation, owner, "0원인 날도 채워서 일별 매출", "READY", OpenStudyAnalyses.ID_POC_007,
                "sales", "2026-11-01 01:01:00", Long.toString(branch));
        jdbc.update("UPDATE ai_executions SET zero_fill_requested=1 WHERE id=?", zeroFill);
        String plain = execution(conversation, owner, "항목별 매출", "READY", OpenStudyAnalyses.ID_POC_008,
                "sales", "2026-11-01 01:02:00", Long.toString(branch));
        try (var s = login(owner)) {
            JsonNode turns = ok(s, "/api/ai/conversations/" + conversation + "?scope=all").get("turns");
            assertThat(turns.get(0).get("response").get("semanticStatus").asString()).isEqualTo("EXPLICIT_ORIGINAL_PAYMENT_DATE");
            assertThat(turns.get(0).get("response").get("summary").get("refund_basis").asString()).isEqualTo("ORIGINAL_PAYMENT_DATE");
            assertThat(turns.get(1).get("response").get("semanticStatus").asString()).isEqualTo("EXPLICIT_ZERO_FILL_OVERRIDE_REVIEW");
            assertThat(turns.get(2).get("response").get("semanticStatus").asString()).isNotEqualTo("EXPLICIT_ORIGINAL_PAYMENT_DATE");
            assertThat(ok(s, "/api/ai/executions/" + original).get("refundBasis").asString()).isEqualTo("original");
            assertThat(ok(s, "/api/ai/executions/" + plain).get("refundBasis").asString()).isEqualTo("processed");
        }
    }

    @Test void reopeningNeverCallsTheModelWhileAskingDoes() throws Exception {
        String conversation = conversation(owner, "모델 호출 확인");
        execution(conversation, owner, "지점별 매출을 비교해줘", "READY", OpenStudyAnalyses.ID_POC_001, "sales", "2026-11-01 01:00:00");
        execution(conversation, owner, "매출", "CLARIFICATION", null, "sales", "2026-11-01 01:01:00");
        try (var s = login(owner)) {
            clearInvocations(gateway);
            JsonNode turns = ok(s, "/api/ai/conversations/" + conversation + "?scope=all").get("turns");
            assertThat(turns.get(0).get("response").get("status").asString()).isEqualTo("COMPLETED");
            ok(s, "/api/ai/conversations/" + conversation);
            verify(gateway, never()).planQuestion(any(), any(), any(), any(), any(), any());

            // The control: asking a question does go through the model entry point.
            var asked = send(s, "POST", "/api/ai/queries", Map.of("question", "지점별 매출을 비교해줘", "scope", "all",
                    "conversationId", conversation));
            assertThat(asked.statusCode()).as(asked.body()).isLessThan(500);
            verify(gateway, times(1)).planQuestion(any(), any(), any(), any(), any(), any());
        }
    }

    @Test void theListFollowsTheLastQuestionAndARenameDoesNotMoveIt() throws Exception {
        String older = conversation(owner, "먼저 만든 대화");
        String newer = conversation(owner, "나중 만든 대화");
        jdbc.update("UPDATE ai_conversations SET updated_at='2026-11-01 01:00:00' WHERE id=?", older);
        jdbc.update("UPDATE ai_conversations SET updated_at='2026-11-01 02:00:00' WHERE id=?", newer);
        try (var s = login(owner)) {
            assertThat(ids(ok(s, "/api/ai/conversations"))).containsExactly(newer, older);
            assertThat(send(s, "PATCH", "/api/ai/conversations/" + older, Map.of("title", "이름만 바꿈")).statusCode()).isEqualTo(200);
            JsonNode renamed = ok(s, "/api/ai/conversations");
            assertThat(ids(renamed)).containsExactly(newer, older);
            assertThat(renamed.get(1).get("title").asString()).isEqualTo("이름만 바꿈");
            assertThat(renamed.get(1).get("lastAskedAt").asString()).startsWith("2026-11-01T01:00");

            var asked = send(s, "POST", "/api/ai/queries", Map.of("question", "지점별 매출을 비교해줘", "scope", "all",
                    "conversationId", older));
            assertThat(asked.statusCode()).as(asked.body()).isLessThan(500);
            assertThat(ids(ok(s, "/api/ai/conversations"))).containsExactly(older, newer);
        }
    }

    static List<String> ids(JsonNode list) {
        var result = new ArrayList<String>();
        for (var item : list) result.add(item.get("id").asString());
        return result;
    }

    @Test void aSavedAnalysisTheCatalogNoLongerOffersShowsAReasonCodeInsteadOfFailing() throws Exception {
        String conversation = conversation(owner, "카탈로그 변경");
        String retired = execution(conversation, owner, "없어진 분석", "READY", "openstudy.retired.analysis", "sales",
                "2026-11-01 01:00:00", Long.toString(branch));
        execution(conversation, owner, "지점별 매출을 비교해줘", "READY", OpenStudyAnalyses.ID_POC_001, "sales", "2026-11-01 01:01:00");
        try (var s = login(owner)) {
            JsonNode turns = ok(s, "/api/ai/conversations/" + conversation + "?scope=all").get("turns");
            JsonNode gone = turns.get(0).get("response");
            assertThat(gone.get("status").asString()).isEqualTo("REFUSED");
            assertThat(gone.get("errorCode").asString()).isEqualTo("ANALYSIS_UNAVAILABLE");
            assertThat(gone.get("httpStatus").asInt()).isEqualTo(422);
            assertThat(gone.get("envelope").isNull()).isTrue();
            // The rest of the conversation still runs.
            assertThat(turns.get(1).get("response").get("status").asString()).isEqualTo("COMPLETED");

            var execution = send(s, "GET", "/api/ai/executions/" + retired, null);
            assertThat(execution.statusCode()).as(execution.body()).isEqualTo(422);
            assertThat(json.readTree(execution.body()).get("code").asString()).isEqualTo("ANALYSIS_UNAVAILABLE");
        }
    }

    @Test void aQuestionThatAsksForAChangeIsNotRunAndEndsAsUnsupported() throws Exception {
        String conversation = "conv-" + UUID.randomUUID().toString().substring(0, 12);
        conversations.add(conversation);
        try (var s = login(owner)) {
            var response = send(s, "POST", "/api/ai/queries", Map.of("question", "이번 주 스터디룸 예약 전부 취소해줘", "scope", "all",
                    "conversationId", conversation));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
            JsonNode body = json.readTree(response.body());
            assertThat(body.get("errorCode").asString()).isEqualTo("UNSUPPORTED_MODIFICATION_INTENT");
            assertThat(body.get("envelope").isNull()).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_executions WHERE conversation_id=? AND status='READY'",
                Long.class, conversation)).isZero();
    }

    /** M10-D06: an employee without sales:read never receives sales figures, whatever the question was about. */
    @Test void aQuestionAboutSomethingElseCarriesNoSalesFigures() throws Exception {
        String conversation = conversation(analyst, "매출 아닌 질문");
        String saved = execution(conversation, analyst, "만료 예정 이용권 알려줘", "READY", OpenStudyAnalyses.ID_POC_006, "passes",
                "2026-11-01 01:00:00", Long.toString(branch));
        try (var s = login(analyst)) {
            JsonNode execution = ok(s, "/api/ai/executions/" + saved);
            // The older result shape (rows of branch sales) is gone (Frontend #84); nothing sales-shaped is left.
            assertThat(execution.has("rows")).isFalse();
            assertThat(execution.toString()).doesNotContain("\"approved\"", "\"refunds\"", "approved_krw");

            String asked = "conv-" + UUID.randomUUID().toString().substring(0, 12);
            conversations.add(asked);
            var response = send(s, "POST", "/api/ai/queries", Map.of("question", "만료 예정 이용권 알려줘", "scope", Long.toString(branch),
                    "conversationId", asked));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            JsonNode body = json.readTree(response.body());
            assertThat(body.get("status").asString()).isEqualTo("COMPLETED");
            assertThat(body.has("legacyExecution")).isFalse();
            assertThat(body.toString()).doesNotContain("approved_krw");
        }
    }

    /** A sales question carries its branch sales in the result itself, since it required sales:read on every branch. */
    @Test void aSalesQuestionCarriesItsBranchSalesInTheResult() throws Exception {
        String conversation = conversation(stranger, "매출 질문");
        String saved = execution(conversation, stranger, "지점별 매출을 비교해줘", "READY", OpenStudyAnalyses.ID_POC_001, "sales",
                "2026-11-01 01:00:00", Long.toString(branch));
        try (var s = login(stranger)) {
            JsonNode execution = ok(s, "/api/ai/executions/" + saved);
            assertThat(execution.has("rows")).isFalse();
            JsonNode rows = null;
            for (var dataset : execution.at("/envelope/datasets")) if (dataset.get("id").asString().equals("branchSales")) rows = dataset.get("rows");
            assertThat(rows).as(execution.toString()).hasSize(1);
            assertThat(rows.get(0).get("branch_id").asString()).isEqualTo(Long.toString(branch));
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

    String execution(String conversation, long employee, String question, String status, String analysisId, String domain,
            String createdAt) {
        return execution(conversation, employee, question, status, analysisId, domain, createdAt, "");
    }

    String execution(String conversation, long employee, String question, String status, String analysisId, String domain,
            String createdAt, String branchCsv) {
        String id = "exec-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO ai_executions(id,request_ref,conversation_id,employee_id,scope_key,branch_ids_csv,question,"
                        + "analysis_id,intent_domain,status,as_of_utc,snapshot_version,policy_version,created_at) "
                        + "VALUES(?,?,?,?,'all',?,?,?,?,?,'2026-11-01 00:00:00','s1','p1',?)",
                id, "req-" + id, conversation, employee, branchCsv, question, analysisId, domain, status, createdAt);
        return id;
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
