package com.openstudy.modules.ai;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import static org.mockito.Mockito.verify;

/**
 * M15 milestone check over HTTP and MySQL (analysis-reuse-api.md): refinement (#125), recent queries (#125), follow-up
 * context (#126) and CSV export (#127). Every reuse path checks the current permissions again and none calls the model.
 */
@MySqlIntegrationTest
class AnalysisReuseIntegrationTests {
    static final String PASSWORD = "ai-reuse-password";

    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired BranchService branchService;
    /** Every model call goes through planQuestion; reuse never reaches it. */
    @MockitoSpyBean OpenStudyPlaybackGateway gateway;
    @LocalServerPort int port;

    final List<Long> people = new ArrayList<>(), branches = new ArrayList<>();
    final List<String> conversations = new ArrayList<>();
    long owner, analyst, seller, branch, other;

    @BeforeEach void setup() {
        owner = person("OWNER");
        analyst = person("EMPLOYEE");
        seller = person("EMPLOYEE");
        branch = branch("재사용 지점");
        other = branch("다른 재사용 지점");
        member(seller, branch, "ai:read", "sales:read");
        // ai:read only: sales results are refused with a reason code.
        member(analyst, branch, "ai:read");
    }

    @AfterEach void clean() {
        String actors = people.stream().map(String::valueOf).collect(Collectors.joining(","));
        jdbc.update("DELETE FROM ai_executions WHERE employee_id IN (" + actors + ")");
        for (String id : conversations) jdbc.update("DELETE FROM ai_conversations WHERE id=?", id);
        jdbc.update("DELETE FROM ai_conversations WHERE employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM ai_traces WHERE employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM audit_events WHERE actor_employee_id IN (" + actors + ")");
        for (long id : branches) {
            jdbc.update("DELETE FROM audit_events WHERE branch_id=?", id);
            jdbc.update("DELETE FROM membership_permission_grants WHERE membership_id IN (SELECT id FROM branch_memberships WHERE branch_id=?)", id);
            jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branch_weekly_hours WHERE branch_id=?", id);
            jdbc.update("DELETE FROM branches WHERE id=?", id);
        }
        jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM employees WHERE id IN (" + actors + ")");
    }

    // ---- #125 refinement -----------------------------------------------------------------------------------------

    @Test void aRefinementRunsWithoutTheModelAndIsSavedAsANewExecutionInTheConversation() throws Exception {
        String conversation = conversation(seller, "매출");
        String saved = execution(conversation, seller, "지점별 매출을 비교해줘", "READY", OpenStudyAnalyses.ID_POC_001, "sales",
                "2026-11-01 01:00:00", Long.toString(branch));
        try (var s = login(seller)) {
            clearInvocations(gateway);
            var refined = send(s, "POST", "/api/ai/executions/" + saved + "/refinements",
                    Map.of("period", Map.of("from", "2026-10-01", "to", "2026-10-31")));
            assertThat(refined.statusCode()).as(refined.body()).isEqualTo(200);
            JsonNode body = json.readTree(refined.body());
            assertThat(body.get("status").asString()).isEqualTo("COMPLETED");
            assertThat(body.at("/conditions/period/from").asString()).isEqualTo("2026-10-01");
            assertThat(body.at("/conditions/periodSource").asString()).isEqualTo("EDITED");
            assertThat(body.at("/context/changed").toString()).isEqualTo("[\"PERIOD\"]");
            assertThat(body.at("/context/basedOnExecutionId").asString()).isEqualTo(saved);
            verify(gateway, never()).planQuestion(any(), any(), any(), any(), any(), any());

            String created = body.get("executionId").asString();
            assertThat(jdbc.queryForMap("SELECT origin, source_execution_id AS source, conversation_id AS conversation, "
                    + "CAST(period_from AS CHAR) AS start FROM ai_executions WHERE id=?", created))
                    .containsEntry("origin", "REFINEMENT").containsEntry("source", saved).containsEntry("conversation", conversation)
                    .containsEntry("start", "2026-10-01");
            // Looking it up again keeps the edited period; the reopened conversation shows where it came from.
            assertThat(ok(s, "/api/ai/executions/" + created).at("/conditions/period/to").asString()).isEqualTo("2026-10-31");
            JsonNode turns = ok(s, "/api/ai/conversations/" + conversation).get("turns");
            assertThat(turns.get(1).get("origin").asString()).isEqualTo("REFINEMENT");
            assertThat(turns.get(1).get("sourceExecutionId").asString()).isEqualTo(saved);
        }
    }

    @Test void aRefinementOutsideTheBranchScopeOrTheEditableConditionsIsRefused() throws Exception {
        String conversation = conversation(seller, "매출");
        String saved = execution(conversation, seller, "지점별 매출을 비교해줘", "READY", OpenStudyAnalyses.ID_POC_001, "sales",
                "2026-11-01 01:00:00", Long.toString(branch));
        try (var s = login(seller)) {
            var outside = send(s, "POST", "/api/ai/executions/" + saved + "/refinements", Map.of("branchIds", List.of(Long.toString(other))));
            assertThat(outside.statusCode()).as(outside.body()).isEqualTo(403);
            assertThat(code(outside)).isEqualTo("BRANCH_SCOPE_DENIED");
            assertThat(outside.body()).doesNotContain("다른 재사용 지점");
            assertThat(code(send(s, "POST", "/api/ai/executions/" + saved + "/refinements", Map.of("productIds", List.of("1")))))
                    .isEqualTo("FILTER_NOT_EDITABLE");
            assertThat(code(send(s, "POST", "/api/ai/executions/" + saved + "/refinements",
                    Map.of("period", Map.of("from", "2025-01-01", "to", "2026-10-31"))))).isEqualTo("PERIOD_TOO_LONG");
            assertThat(code(send(s, "POST", "/api/ai/executions/" + saved + "/refinements",
                    Map.of("branchIds", List.of(Long.toString(branch)))))).isEqualTo("NO_FILTER_CHANGE");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_executions WHERE source_execution_id=?", Long.class, saved)).isZero();
        // Another employee, the owner included, does not see the execution at all.
        try (var s = login(owner)) {
            assertThat(send(s, "POST", "/api/ai/executions/" + saved + "/refinements",
                    Map.of("period", Map.of("from", "2026-10-01", "to", "2026-10-31"))).statusCode()).isEqualTo(404);
        }
    }

    // ---- #125 recent queries and table pages ---------------------------------------------------------------------

    @Test void recentQueriesAreTheEmployeesOwnQuestionsOfTheLastThirtyDays() throws Exception {
        String conversation = conversation(seller, "최근 질의");
        String ran = execution(conversation, seller, "지점별 매출을 비교해줘", "READY", OpenStudyAnalyses.ID_POC_001, "sales",
                "2026-11-01 02:00:00", Long.toString(branch));
        execution(conversation, seller, "매출", "CLARIFICATION", null, "sales", "2026-11-01 01:00:00", "");
        execution(conversation, seller, "아주 오래된 질문", "READY", OpenStudyAnalyses.ID_POC_001, "sales", "2026-08-01 01:00:00",
                Long.toString(branch));
        String refinement = execution(conversation, seller, "지점별 매출을 비교해줘", "READY", OpenStudyAnalyses.ID_POC_001, "sales",
                "2026-11-01 03:00:00", Long.toString(branch));
        jdbc.update("UPDATE ai_executions SET origin='REFINEMENT', source_execution_id=? WHERE id=?", ran, refinement);
        execution(conversation(owner, "남의 대화"), owner, "남의 질문", "READY", OpenStudyAnalyses.ID_POC_001, "sales",
                "2026-11-01 04:00:00", Long.toString(branch));
        try (var s = login(seller)) {
            JsonNode items = ok(s, "/api/ai/recent-queries").get("items");
            assertThat(items).hasSize(2);
            assertThat(items.get(0).get("executionId").asString()).isEqualTo(ran);
            assertThat(items.get(0).get("rerunnable").asBoolean()).isTrue();
            assertThat(items.get(0).get("conversationTitle").asString()).isEqualTo("최근 질의");
            assertThat(items.get(1).get("executionId").isNull()).isTrue();
            assertThat(items.get(1).get("rerunnable").asBoolean()).isFalse();
            assertThat(items.toString()).doesNotContain("남의 질문", "아주 오래된 질문");
        }
    }

    @Test void aTablePageIsOneDatasetRunAgainAndAnUnknownColumnIsRefused() throws Exception {
        String saved = execution(conversation(seller, "페이지"), seller, "지점별 매출을 비교해줘", "READY", OpenStudyAnalyses.ID_POC_001,
                "sales", "2026-11-01 01:00:00", Long.toString(branch));
        try (var s = login(seller)) {
            JsonNode paged = ok(s, "/api/ai/executions/" + saved + "?datasetId=branchSales&page=1&size=5&sort=net_krw&direction=asc");
            JsonNode dataset = null;
            for (var d : paged.at("/envelope/datasets")) if (d.get("id").asString().equals("branchSales")) dataset = d;
            assertThat(dataset).isNotNull();
            assertThat(dataset.get("page").asInt()).isEqualTo(1);
            assertThat(dataset.get("size").asInt()).isEqualTo(5);
            assertThat(paged.get("editableFilters").get("products").asBoolean()).isFalse();
            assertThat(code(send(s, "GET", "/api/ai/executions/" + saved + "?sort=secret", null))).isEqualTo("VALIDATION_FAILED");
        }
        // A reuse path refuses with the reason, not a narrowed result.
        try (var s = login(analyst)) {
            String own = execution(conversation(analyst, "권한"), analyst, "지점별 매출을 비교해줘", "READY", OpenStudyAnalyses.ID_POC_001,
                    "sales", "2026-11-01 01:00:00", Long.toString(branch));
            var denied = send(s, "GET", "/api/ai/executions/" + own + "?page=1", null);
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(code(denied)).isEqualTo("SALES_PERMISSION_DENIED");
        }
    }

    // ---- #126 follow-up context ----------------------------------------------------------------------------------

    @Test void aFollowUpTakesOverThePreviousResultAndTheContextIsCutWhenScopeOrPermissionChanges() throws Exception {
        String conversation = "conv-" + UUID.randomUUID().toString().substring(0, 12);
        conversations.add(conversation);
        String scope = Long.toString(branch);
        // A second branch to switch the scope to (a non-owner cannot ask with scope "all").
        member(seller, other, "ai:read");
        try (var s = login(seller)) {
            // A non-owner with one branch asks about that branch ("지점별" across branches is refused by M10).
            JsonNode first = ask(s, "지난달 일별 매출 추이를 보여줘", scope, conversation);
            assertThat(first.at("/context/resetReason").asString()).isEqualTo("NO_PRIOR_RESULT");
            assertThat(first.at("/context/reset").asBoolean()).isFalse();

            // A follow-up without an analysis of its own takes over the previous one.
            JsonNode again = ask(s, "같은 조건으로 다시 보여줘", scope, conversation);
            assertThat(again.get("analysisId").asString()).isEqualTo(OpenStudyAnalyses.ID_POC_007);
            assertThat(again.at("/context/basedOnExecutionId").asString()).isEqualTo(first.get("executionId").asString());
            assertThat(again.at("/context/inherited").toString()).contains("ANALYSIS", "PERIOD", "BRANCHES");

            // Spelling variants pick the same analysis (normalization).
            assertThat(ask(s, "저번달 일별매상 추이", scope, conversation).get("analysisId").asString()).isEqualTo(OpenStudyAnalyses.ID_POC_007);

            JsonNode otherScope = ask(s, "만료 예정 이용권 알려줘", Long.toString(other), conversation);
            assertThat(otherScope.at("/context/resetReason").asString()).isEqualTo("SCOPE_CHANGED");
            assertThat(otherScope.at("/context/reset").asBoolean()).isTrue();
        }
        // The previous result was sales; without sales:read the context is cut instead of reused.
        jdbc.update("DELETE g FROM membership_permission_grants g JOIN branch_memberships m ON m.id=g.membership_id "
                + "JOIN permission_definitions p ON p.id=g.permission_id WHERE m.employee_id=? AND p.code='sales:read'", seller);
        String salesConversation = conversation(seller, "권한 회수");
        execution(salesConversation, seller, "지점별 매출을 비교해줘", "READY", OpenStudyAnalyses.ID_POC_001, "sales",
                "2026-11-01 01:00:00", Long.toString(branch));
        jdbc.update("UPDATE ai_executions SET scope_key=? WHERE conversation_id=?", scope, salesConversation);
        try (var s = login(seller)) {
            JsonNode cut = ask(s, "만료 예정 이용권 알려줘", scope, salesConversation);
            assertThat(cut.at("/context/resetReason").asString()).isEqualTo("PERMISSION_LOST");
            assertThat(cut.toString()).doesNotContain("approved_krw");
        }
    }

    // ---- #127 CSV export -----------------------------------------------------------------------------------------

    @Test void aSalesExportHasTheScreenFiguresAndIsAudited() throws Exception {
        String query = "?branchIds=" + branch;
        try (var s = login(seller)) {
            JsonNode screen = ok(s, "/api/sales/revenue/branches" + query);
            var file = send(s, "GET", "/api/sales/exports/revenue-branches" + query, null);
            assertThat(file.statusCode()).as(file.body()).isEqualTo(200);
            assertThat(file.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
            assertThat(file.headers().firstValue("Content-Disposition").orElse("")).startsWith("attachment; filename*=UTF-8''");
            assertThat(file.headers().firstValue("Cache-Control").orElse("")).isEqualTo("no-store");
            String csv = file.body();
            assertThat(csv).startsWith("﻿항목,값\r\n").contains("재사용 지점", "단위,원\r\n", "잘림,아니오\r\n");
            String[] lines = csv.split("\r\n");
            int header = List.of(lines).indexOf("지점,승인 금액(원),환불 금액(원),순매출(원),승인 결제 수(건),환불 수(건),데이터 상태");
            assertThat(header).as(csv).isPositive();
            JsonNode items = screen.get("items");
            assertThat(lines.length - header - 1).isEqualTo(items.size());
            for (int i = 0; i < items.size(); i++) {
                String[] cells = lines[header + 1 + i].split(",");
                assertThat(cells[3]).isEqualTo(items.get(i).get("netAmountKrw").asString());
            }
            assertThat(code(send(s, "GET", "/api/sales/exports/unknown" + query, null))).isEqualTo("EXPORT_REPORT_UNKNOWN");
            assertThat(send(s, "GET", "/api/sales/exports/ledger" + query + "&page=1", null).statusCode()).isEqualTo(422);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE actor_employee_id=? AND entity_type='sales-report' "
                + "AND action='EXPORTED' AND after_json LIKE '%revenue-branches%'", Long.class, seller)).isEqualTo(1);
        // The same permission as the screen: no sales:read, no file.
        try (var s = login(analyst)) {
            assertThat(send(s, "GET", "/api/sales/exports/revenue-branches" + query, null).statusCode()).isEqualTo(403);
        }
    }

    @Test void anAiExportRunsTheExecutionAgainUnderTheCurrentPermissions() throws Exception {
        String saved = execution(conversation(seller, "내보내기"), seller, "지점별 매출을 비교해줘", "READY", OpenStudyAnalyses.ID_POC_001,
                "sales", "2026-11-01 01:00:00", Long.toString(branch));
        try (var s = login(seller)) {
            clearInvocations(gateway);
            var file = send(s, "GET", "/api/ai/executions/" + saved + "/export?datasetId=branchSales", null);
            assertThat(file.statusCode()).as(file.body()).isEqualTo(200);
            assertThat(new String(file.body().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).startsWith("﻿항목,값")
                    .contains("재사용 지점", "branch_name");
            verify(gateway, never()).planQuestion(any(), any(), any(), any(), any(), any());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE actor_employee_id=? AND entity_type='ai-execution' "
                + "AND action='EXPORTED' AND after_json LIKE ?", Long.class, seller, "%" + saved + "%")).isEqualTo(1);
        try (var s = login(owner)) {
            assertThat(send(s, "GET", "/api/ai/executions/" + saved + "/export", null).statusCode()).isEqualTo(404);
        }
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------

    JsonNode ask(Session s, String question, String scope, String conversation) throws Exception {
        var response = send(s, "POST", "/api/ai/queries", Map.of("question", question, "scope", scope, "conversationId", conversation));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("status").asString()).as(response.body()).isEqualTo("COMPLETED");
        return body;
    }

    String code(HttpResponse<String> response) throws Exception {
        JsonNode body = json.readTree(response.body());
        return body.path("code").asString(body.path("errorCode").asString(""));
    }

    long branch(String name) {
        var hours = IntStream.rangeClosed(1, 7).mapToObj(day -> new WeeklyHours(day, false, 0, 1440)).toList();
        long id = Long.parseLong(branchService.create(owner, UUID.randomUUID().toString(),
                new BranchRequests.Create(name, "서울", "02-123", hours, "운영 중"), "ai-reuse").id());
        branches.add(id);
        return id;
    }

    long person(String role) {
        String email = UUID.randomUUID() + "@ai-reuse.test";
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
            String createdAt, String branchCsv) {
        String id = "exec-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO ai_executions(id,request_ref,conversation_id,employee_id,scope_key,branch_ids_csv,question,"
                        + "analysis_id,intent_domain,status,as_of_utc,snapshot_version,policy_version,created_at) "
                        + "VALUES(?,?,?,?,'all',?,?,?,?,?,'2026-11-01 00:00:00','s1','p1',?)",
                id, "req-" + id, conversation, employee, branchCsv, question, analysisId, domain, status, createdAt);
        return id;
    }

    // ---- HTTP -----------------------------------------------------------------------------------------------------

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
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
