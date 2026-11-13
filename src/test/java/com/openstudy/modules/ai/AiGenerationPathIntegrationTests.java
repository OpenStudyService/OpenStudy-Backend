package com.openstudy.modules.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.openstudy.domainlens.api.error.EngineErrorCode;
import com.openstudy.domainlens.api.error.EngineException;
import com.openstudy.domainlens.api.ir.IrDocument;
import com.openstudy.domainlens.api.ir.IrStatus;
import com.openstudy.domainlens.api.ir.QuerySpec;
import com.openstudy.domainlens.api.planning.PlanningPrompt;
import com.openstudy.domainlens.api.planning.SemanticPlan;
import com.openstudy.domainlens.api.prompt.GenerationBudget;
import com.openstudy.domainlens.api.spi.ModelGateway;
import com.openstudy.modules.ai.service.OpenStudyAnalyses;
import com.openstudy.modules.ai.service.OpenStudyGenerationModel;
import com.openstudy.support.database.MySqlIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

/**
 * #233: the AI chat answers a question no registered analysis answers through the DomainLens generation path. The
 * model is replaced by a gateway replaying a semantic plan like the one qwen3.8-flash returned (#232); everything after
 * it (grounding, validation, permissions, JPA, view binding, saving, rerun, CSV) is the real server.
 */
@MySqlIntegrationTest
@EnabledIf("com.openstudy.domainlens.LocalAiFixtures#modelInstalled")
class AiGenerationPathIntegrationTests extends AiTestSupport {
    static final String QUESTION = "이번 달 지점별 결제 금액 합계 보여줘";
    static final String METRIC = "sales.approved_amount_krw";
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @MockitoBean OpenStudyGenerationModel model;

    final AtomicInteger modelCalls = new AtomicInteger();
    Supplier<SemanticPlan> answer = () -> plan(QUESTION);
    final List<Long> customerRows = new ArrayList<>();
    long owner, first, second;

    @BeforeEach void setUp() {
        when(model.gateway()).thenReturn(Optional.of(replay()));
        owner = person("OWNER");
        first = branch(owner, "생성가" + UUID.randomUUID().toString().substring(0, 4) + "점");
        second = branch(owner, "생성나" + UUID.randomUUID().toString().substring(0, 4) + "점");
        long customer = customer();
        Instant now = Instant.now();
        pay(customer, first, 30_000, "APPROVED", now);
        pay(customer, first, 12_000, "APPROVED", now);
        pay(customer, second, 50_000, "APPROVED", now);
        pay(customer, second, 99_000, "FAILED", now);
        pay(customer, second, 7_000, "APPROVED", now.minus(Duration.ofDays(100)));
    }

    @AfterEach void cleanPayments() {
        for (long id : customerRows) {
            jdbc.update("DELETE FROM payments WHERE customer_id=?", id);
            jdbc.update("DELETE FROM customers WHERE id=?", id);
        }
    }

    @Test void anUnregisteredQuestionIsAnsweredByTheGeneratedQuery() throws Exception {
        try (var s = login(owner)) {
            var response = ask(s, QUESTION, "all");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            JsonNode body = body(response);
            assertThat(body.get("status").asString()).isEqualTo("COMPLETED");
            assertThat(body.get("resultCategory").asString()).isEqualTo("GENERATED_EXECUTION");
            assertThat(body.get("analysisId").isNull()).isTrue();
            assertThat(body.at("/querySpec/metrics/0/field").asString()).isEqualTo(METRIC);
            assertThat(body.at("/querySpec/mode").asString()).isEqualTo("AGGREGATE");
            assertThat(body.at("/viewSpec/components/0/componentRef/id").asString()).isNotBlank();
            // AiContext contract: a generated answer builds on no earlier result, so its lists are present and empty.
            assertThat(body.at("/context/inherited").isArray()).as(body.toString()).isTrue();
            assertThat(body.at("/context/changed").isArray()).isTrue();
            assertThat(body.at("/context/reset").asBoolean()).isFalse();
            var sums = sums(body.at("/envelope/datasets/0/rows"));
            assertThat(sums).containsEntry(name(first), approvedThisMonth(first)).containsEntry(name(second), approvedThisMonth(second));
            assertThat(modelCalls).hasValue(1);
            String executionId = body.get("executionId").asString();
            assertThat(jdbc.queryForObject("SELECT query_spec_json FROM ai_executions WHERE id=?", String.class, executionId))
                    .contains(METRIC);
        }
    }

    @Test void aSavedGeneratedResultRunsAgainAndExportsWithoutTheModel() throws Exception {
        try (var s = login(owner)) {
            JsonNode asked = body(ask(s, QUESTION, "all"));
            String executionId = asked.get("executionId").asString();
            String conversationId = asked.get("conversationId").asString();
            modelCalls.set(0);

            var reopened = send(s, "GET", "/api/ai/conversations/" + conversationId, null);
            assertThat(reopened.statusCode()).as(reopened.body()).isEqualTo(200);
            JsonNode turn = body(reopened).at("/turns/0/response");
            assertThat(turn.get("status").asString()).as(turn.toString()).isEqualTo("COMPLETED");
            assertThat(sums(turn.at("/envelope/datasets/0/rows"))).containsEntry(name(second), approvedThisMonth(second));

            var execution = send(s, "GET", "/api/ai/executions/" + executionId, null);
            assertThat(execution.statusCode()).as(execution.body()).isEqualTo(200);
            assertThat(sums(body(execution).at("/envelope/datasets/0/rows"))).containsEntry(name(first), approvedThisMonth(first));

            var export = send(s, "GET", "/api/ai/executions/" + executionId + "/export", null);
            assertThat(export.statusCode()).as(export.body()).isEqualTo(200);
            assertThat(export.body()).contains(name(first)).contains("42000").contains("AI 생성 조회");
            // Columns are named after what they select, not the compiler's aliases; no registered-analysis terms.
            var month = YearMonth.now(KST);
            assertThat(export.body()).contains("지점명,결제 승인액(KRW)").doesNotContain("d0,m0")
                    .contains("지표 결제 승인액").contains("묶음 지점명")
                    .contains("기간 " + month.atDay(1) + "~" + month.atEndOfMonth())
                    .doesNotContain("환불 기준").doesNotContain("데이터셋 q0");
            assertThat(body(execution).at("/conditions/refundBasis").isNull()).isTrue();

            var recent = body(send(s, "GET", "/api/ai/recent-queries", null)).get("items");
            JsonNode item = null;
            for (var candidate : recent) if (executionId.equals(candidate.get("executionId").asString(null))) item = candidate;
            assertThat(item).isNotNull();
            assertThat(item.get("rerunnable").asBoolean()).isTrue();
            assertThat(modelCalls).hasValue(0);
        }
    }

    @Test void registeredQuestionsStayWithTheirAnalysis() throws Exception {
        try (var s = login(owner)) {
            JsonNode body = body(ask(s, "이번 달 지점별 매출을 비교해줘", "all"));
            assertThat(body.get("analysisId").asString()).isEqualTo(OpenStudyAnalyses.ID_POC_001);
            assertThat(modelCalls).hasValue(0);
        }
    }

    @Test void plainlyOffDomainQuestionsNeverReachTheModel() throws Exception {
        try (var s = login(owner)) {
            var response = ask(s, "내일 날씨 알려줘", "all");
            assertThat(body(response).get("errorCode").asString()).isEqualTo("OUT_OF_DOMAIN");
            assertThat(modelCalls).hasValue(0);
        }
    }

    @Test void staffWithoutSalesReadIsRefusedBeforeTheModel() throws Exception {
        long staff = person("EMPLOYEE");
        member(staff, first, "ai:read");
        try (var s = login(staff)) {
            var response = ask(s, QUESTION, "all");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
            assertThat(response.body()).doesNotContain("42000");
            assertThat(modelCalls).hasValue(0);
        }
    }

    @Test void aModelTimeoutIsACommonErrorResponse() throws Exception {
        answer = () -> { throw new EngineException(EngineErrorCode.MODEL_TIMEOUT, "deadline"); };
        try (var s = login(owner)) {
            var response = ask(s, QUESTION, "all");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(504);
            assertThat(body(response).get("errorCode").asString()).isEqualTo("AI_TIMEOUT");
        }
    }

    @Test void whenTheGenerationPathCannotAnswerEitherTheQuestionStaysUnsupported() throws Exception {
        answer = () -> new SemanticPlan(SemanticPlan.VERSION, IrStatus.UNSUPPORTED, "모르는 지표", List.of(), List.of(), List.of(), null);
        try (var s = login(owner)) {
            JsonNode body = body(ask(s, QUESTION, "all"));
            assertThat(body.get("status").asString()).isEqualTo("UNSUPPORTED");
            assertThat(body.get("errorCode").asString()).isEqualTo("OUT_OF_DOMAIN");
            assertThat(modelCalls).hasValue(1);
        }
    }

    @Test void withoutALiveModelNothingChanges() throws Exception {
        when(model.gateway()).thenReturn(Optional.empty());
        try (var s = login(owner)) {
            JsonNode body = body(ask(s, QUESTION, "all"));
            assertThat(body.get("errorCode").asString()).isEqualTo("OUT_OF_DOMAIN");
            assertThat(modelCalls).hasValue(0);
        }
    }

    // --- replayed model ---

    ModelGateway replay() {
        return new ModelGateway() {
            @Override public boolean supportsPlanning() { return true; }
            @Override public SemanticPlan plan(PlanningPrompt prompt, GenerationBudget budget, SemanticPlan previous, String failure) {
                modelCalls.incrementAndGet();
                return answer.get();
            }
            @Override public IrDocument generate(String ask, List<String> refs) { throw new AssertionError("planning only"); }
        };
    }

    /** Approved payment amount by branch name, no filter: the server supplies "이번 달" (#232 analyzer). */
    static SemanticPlan plan(String question) {
        var evidence = List.of(new SemanticPlan.Evidence(METRIC, SemanticPlan.Origin.QUESTION, question),
                new SemanticPlan.Evidence("branch.name", SemanticPlan.Origin.QUESTION, question));
        var request = new SemanticPlan.Request(QuerySpec.Mode.AGGREGATE, null, null, List.of(METRIC), List.of("branch.name"), null,
                List.of(), null, null, null, false, List.of(), evidence, List.of());
        return new SemanticPlan(SemanticPlan.VERSION, IrStatus.READY, null, List.of(), List.of(request), List.of(), null);
    }

    // --- fixtures ---

    long customer() {
        String no = "GEN-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO customers(member_no,name) VALUES(?,'생성 고객')", no);
        long id = jdbc.queryForObject("SELECT id FROM customers WHERE member_no=?", Long.class, no);
        customerRows.add(id);
        return id;
    }

    void pay(long customer, long branch, long amount, String status, Instant at) {
        jdbc.update("INSERT INTO payments(customer_id,branch_id,purpose,method,amount_krw,status,idempotency_key,issued_pass_quantity,approved_at,failed_at,created_by) "
                        + "VALUES(?,?,'LOCKER','CASH',?,?,?,0,?,?,?)",
                customer, branch, amount, status, "GEN-" + UUID.randomUUID(),
                "APPROVED".equals(status) ? Timestamp.from(at) : null, "FAILED".equals(status) ? Timestamp.from(at) : null, owner);
    }

    long approvedThisMonth(long branch) {
        var month = YearMonth.now(KST);
        return count("SELECT COALESCE(SUM(amount_krw),0) FROM payments WHERE branch_id=? AND status='APPROVED' AND approved_at>=? AND approved_at<?",
                branch, Timestamp.from(month.atDay(1).atStartOfDay(KST).toInstant()), Timestamp.from(month.plusMonths(1).atDay(1).atStartOfDay(KST).toInstant()));
    }

    String name(long branch) {
        return jdbc.queryForObject("SELECT name FROM branches WHERE id=?", String.class, branch);
    }

    static Map<String, Long> sums(JsonNode rows) {
        var result = new HashMap<String, Long>();
        for (var row : rows) {
            var values = new ArrayList<JsonNode>();
            row.forEach(values::add);
            result.put(values.get(0).asString(), values.get(1).asLong());
        }
        return result;
    }
}
