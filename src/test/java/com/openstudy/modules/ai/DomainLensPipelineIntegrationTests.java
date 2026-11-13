package com.openstudy.modules.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openstudy.domainlens.api.error.EngineException;
import com.openstudy.domainlens.api.ir.IrDocument;
import com.openstudy.domainlens.api.ir.IrStatus;
import com.openstudy.domainlens.api.ir.Layout;
import com.openstudy.domainlens.api.ir.QuerySpec;
import com.openstudy.domainlens.api.ir.ViewSpec;
import com.openstudy.domainlens.api.spi.ModelGateway;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.api.work.Cancellation;
import com.openstudy.modules.ai.service.OpenStudyQueryPipelineFactory;
import com.openstudy.modules.ai.service.OpenStudyUserContextResolver;
import com.openstudy.support.database.MySqlIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * #232 stage 0: the DomainLens generation path runs on the OpenStudy schema. A recorded model answer (an aggregate
 * QuerySpec and its ViewSpec) is validated, planned under the requester's policies, executed by JPA and bound to the
 * view; the numbers come from the database, not from the model.
 */
@MySqlIntegrationTest
class DomainLensPipelineIntegrationTests extends AiTestSupport {
    /** The approved context retrieval hands the model for net sales: the metric with every dependency it declares. */
    static final Set<String> SALES_CONTEXT = Set.of("sales.net_sales_krw", "payment", "payment.amount_krw", "payment.branch_id",
            "payment.paid_at", "payment.status", "payment.branch", "branch", "branch.name",
            "refund", "refund.payment", "refund.refunded_at", "refund.amount_krw");

    @Autowired OpenStudyQueryPipelineFactory pipelines;
    @Autowired OpenStudyUserContextResolver actors;

    final List<Long> customerRows = new ArrayList<>();
    long owner, first, second;

    @BeforeEach void sales() {
        owner = person("OWNER");
        first = branch(owner, "엔진가" + UUID.randomUUID().toString().substring(0, 4) + "점");
        second = branch(owner, "엔진나" + UUID.randomUUID().toString().substring(0, 4) + "점");
        long customer = customer();
        pay(customer, first, 30_000, "APPROVED");
        pay(customer, first, 12_000, "APPROVED");
        pay(customer, second, 50_000, "APPROVED");
        pay(customer, second, 99_000, "FAILED");
    }

    @AfterEach void cleanSales() {
        for (long id : customerRows) {
            jdbc.update("DELETE FROM payments WHERE customer_id=?", id);
            jdbc.update("DELETE FROM customers WHERE id=?", id);
        }
    }

    @Test void recordedAggregateRunsThroughValidationPlanningJpaAndBinding() {
        var outcome = pipelines.create(answer(salesByBranch())).run("지점별 결제 금액 합계", SALES_CONTEXT, user(owner, Set.of(first, second)), new Cancellation());

        assertThat(outcome.ir().status()).isEqualTo(IrStatus.READY);
        assertThat(outcome.result().status()).isEqualTo("COMPLETED");
        assertThat(outcome.plans()).hasSize(1);
        var rows = outcome.result().datasets().getFirst().rows();
        assertThat(sums(rows)).isEqualTo(Map.of(first, rawAmountSum(first), second, rawAmountSum(second)));
        assertThat(outcome.result().view()).isNotNull();
    }

    @Test void branchScopeLimitsTheRowsTheGeneratedQueryReads() {
        var outcome = pipelines.create(answer(salesByBranch())).run("지점별 결제 금액 합계", SALES_CONTEXT, user(owner, Set.of(first)), new Cancellation());

        assertThat(sums(outcome.result().datasets().getFirst().rows())).isEqualTo(Map.of(first, rawAmountSum(first)));
    }

    @Test
    @EnabledIf("com.openstudy.domainlens.LocalAiFixtures#modelInstalled")
    void retrievalSuppliesTheContextTheRecordedAnswerUses() {
        var context = new AtomicReference<List<String>>();
        ModelGateway recording = (ask, refs) -> { context.set(refs); return salesByBranch(); };

        var outcome = pipelines.create(recording).run("지점별 매출 합계 알려줘", user(owner, Set.of(first, second)), new Cancellation(), pipelines.retrieval());

        assertThat(context.get()).containsAll(SALES_CONTEXT);
        assertThat(sums(outcome.result().datasets().getFirst().rows())).isEqualTo(Map.of(first, rawAmountSum(first), second, rawAmountSum(second)));
    }

    /**
     * Replays the semantic plan qwen3.8-flash returned on 2026-11-13 for "이번 달 지점별 매출 합계 보여줘": net sales by
     * branch.name with the model's own this-month range. The server does not accept a relative period the model
     * computed; only an explicit "YYYY년 M월부터 …까지" or a period resolved by a consumer intent analyzer grounds it,
     * and OpenStudy has no analyzer yet (#232 stage 1). So the request stops at the period check.
     */
    @Test
    @EnabledIf("com.openstudy.domainlens.LocalAiFixtures#modelInstalled")
    void recordedLivePlanStopsAtTheRelativePeriodUntilAnIntentAnalyzerResolvesIt() throws Exception {
        var outcome = pipelines.create(replay(livePlan())).run("이번 달 지점별 매출 합계 보여줘", user(owner, Set.of(first, second)), new Cancellation(), pipelines.retrieval());

        assertThat(outcome.ir().status()).isEqualTo(IrStatus.UNSUPPORTED);
        assertThat(outcome.ir().unsupportedReason()).isEqualTo("요청한 필터 조건과 기간의 근거를 검증하지 못했습니다.");
        assertThat(outcome.plans()).isEmpty();
    }

    /** The same model plan without the period: metric and dimension are grounded by catalog labels and it executes. */
    @Test
    @EnabledIf("com.openstudy.domainlens.LocalAiFixtures#modelInstalled")
    void recordedLivePlanWithoutAPeriodRunsAndMatchesTheDatabase() throws Exception {
        var live = livePlan();
        var request = live.requests().getFirst();
        var unfiltered = new com.openstudy.domainlens.api.planning.SemanticPlan.Request(request.kind(), request.source(), request.analysisId(),
                request.metrics(), request.dimensions(), null, request.order(), request.page(), request.size(), request.limit(), request.total(),
                request.presentations(), request.evidence().stream().filter(e -> !e.slot().equals("filter")).map(e ->
                        new com.openstudy.domainlens.api.planning.SemanticPlan.Evidence(e.slot(), e.origin(), "지점별 매출 합계 보여줘")).toList(),
                request.formulas());
        var plan = new com.openstudy.domainlens.api.planning.SemanticPlan(live.version(), live.status(), live.reason(), live.options(),
                List.of(unfiltered), live.unresolved(), live.reasonCode());

        var outcome = pipelines.create(replay(plan)).run("지점별 매출 합계 보여줘", user(owner, Set.of(first, second)), new Cancellation(), pipelines.retrieval());

        assertThat(outcome.ir().status()).as(com.openstudy.domainlens.api.ir.ContractJson.write(outcome.ir())).isEqualTo(IrStatus.READY);
        var rows = outcome.result().datasets().getFirst().rows();
        var byName = new java.util.HashMap<String, Long>();
        for (var row : rows) {
            var values = new ArrayList<>(row.values());
            byName.put(values.get(0).toString(), ((Number) values.get(1)).longValue());
        }
        assertThat(byName).containsEntry(branchName(first), rawAmountSum(first)).containsEntry(branchName(second), rawAmountSum(second));
    }

    com.openstudy.domainlens.api.planning.SemanticPlan livePlan() throws java.io.IOException {
        var json = new String(getClass().getResourceAsStream("/domainlens/live-plan-branch-sales.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return com.openstudy.domainlens.api.ir.ContractJson.read(json, com.openstudy.domainlens.api.planning.SemanticPlan.class);
    }

    static ModelGateway replay(com.openstudy.domainlens.api.planning.SemanticPlan plan) {
        return new ModelGateway() {
            @Override public boolean supportsPlanning() { return true; }
            @Override public com.openstudy.domainlens.api.planning.SemanticPlan plan(com.openstudy.domainlens.api.planning.PlanningPrompt prompt,
                    com.openstudy.domainlens.api.prompt.GenerationBudget budget, com.openstudy.domainlens.api.planning.SemanticPlan previous, String failure) {
                return plan;
            }
            @Override public IrDocument generate(String ask, List<String> refs) { throw new AssertionError("planning only"); }
        };
    }

    String branchName(long branch) {
        return jdbc.queryForObject("SELECT name FROM branches WHERE id=?", String.class, branch);
    }

    /**
     * One live Qwen round trip through the same pipeline, opt-in only (OPENSTUDY_AI_LIVE_TESTS=true with QWEN_API_KEY,
     * QWEN_BASE_URL and QWEN_MODEL set): it spends tokens on the configured account. Both model output styles run
     * (direct IR and a semantic plan compiled by the server); the generated IR and outcome are written to
     * build/live-qwen.txt for the development log.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "OPENSTUDY_AI_LIVE_TESTS", matches = "true")
    void liveQwenGeneratesAQueryTheServerValidatesAndRuns() throws Exception {
        var report = new StringBuilder();
        for (boolean planning : List.of(false, true)) {
            var gateway = com.openstudy.domainlens.ai.LiveModelGatewayFactory.create(System.getenv("QWEN_BASE_URL"),
                    System.getenv("QWEN_API_KEY"), System.getenv("QWEN_MODEL"), java.time.Duration.ofSeconds(60),
                    com.openstudy.domainlens.api.prompt.PromptLimits.defaults(), false).planning(planning);
            long started = System.nanoTime();
            report.append("== planning=").append(planning).append(System.lineSeparator());
            try {
                var outcome = pipelines.create(recordingPlans(gateway, report)).planningObservations(
                        prompt -> report.append("promptBytes=").append(com.openstudy.domainlens.api.ir.ContractJson.write(prompt)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length).append(System.lineSeparator()),
                        compiled -> report.append("compiled=").append(com.openstudy.domainlens.api.ir.ContractJson.write(compiled)).append(System.lineSeparator())
                ).run("이번 달 지점별 매출 합계 보여줘", user(owner, Set.of(first, second)), new Cancellation(), pipelines.retrieval());
                report.append("ir=").append(com.openstudy.domainlens.api.ir.ContractJson.write(outcome.ir())).append(System.lineSeparator())
                        .append("status=").append(outcome.result().status()).append(System.lineSeparator())
                        .append("rows=").append(outcome.result().datasets().stream().map(d -> d.rows().toString()).toList()).append(System.lineSeparator())
                        .append("usage=").append(outcome.usage()).append(System.lineSeparator());
            } catch (EngineException ex) {
                report.append("error=").append(ex.code()).append(' ').append(ex.getMessage()).append(System.lineSeparator());
            }
            report.append("ms=").append((System.nanoTime() - started) / 1_000_000).append(System.lineSeparator());
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/live-qwen.txt"), report.toString());
        assertThat(report).contains("planning=true");
    }

    /** Delegates to the live gateway and writes each raw semantic plan the model returns, before the server compiles it. */
    static ModelGateway recordingPlans(ModelGateway live, StringBuilder report) {
        return new ModelGateway() {
            @Override public boolean supportsPlanning() { return live.supportsPlanning(); }
            @Override public boolean supportsRepair() { return live.supportsRepair(); }
            @Override public java.time.Duration requestTimeout() { return live.requestTimeout(); }
            @Override public com.openstudy.domainlens.api.planning.SemanticPlan plan(
                    com.openstudy.domainlens.api.planning.PlanningPrompt prompt, com.openstudy.domainlens.api.prompt.GenerationBudget budget,
                    com.openstudy.domainlens.api.planning.SemanticPlan previous, String failure) {
                var plan = live.plan(prompt, budget, previous, failure);
                report.append("plan=").append(com.openstudy.domainlens.api.ir.ContractJson.write(plan)).append(System.lineSeparator());
                return plan;
            }
            @Override public IrDocument generate(com.openstudy.domainlens.api.prompt.PromptPackage prompt, com.openstudy.domainlens.api.prompt.GenerationBudget budget) {
                return live.generate(prompt, budget);
            }
            @Override public IrDocument repair(com.openstudy.domainlens.api.prompt.PromptPackage prompt, IrDocument previous, String failure,
                    com.openstudy.domainlens.api.prompt.GenerationBudget budget) {
                return live.repair(prompt, previous, failure, budget);
            }
            @Override public IrDocument generate(String ask, List<String> contextRefs) { return live.generate(ask, contextRefs); }
        };
    }

    @Test void unsupportedAnswerStopsWithoutExecuting() {
        var unsupported = new IrDocument(IrDocument.VERSION, IrStatus.UNSUPPORTED, null, List.of(), "OUT_OF_DOMAIN",
                List.of(), null, List.of());
        var outcome = pipelines.create(answer(unsupported)).run("내일 날씨 알려줘", SALES_CONTEXT, user(owner, Set.of(first)), new Cancellation());

        assertThat(outcome.ir().status()).isEqualTo(IrStatus.UNSUPPORTED);
        assertThat(outcome.plans()).isEmpty();
        assertThat(outcome.result().datasets()).isEmpty();
    }

    @Test void unregisteredFieldInTheAnswerIsRejected() {
        var query = new QuerySpec("sales", "payment", "payment", QuerySpec.Mode.AGGREGATE,
                List.of(new QuerySpec.Selection("customer.phone", "phone")),
                List.of(new QuerySpec.Selection("sales.net_sales_krw", "net_sales")),
                null, List.of(), new QuerySpec.Page(0, 50), 50, null);
        var forged = new IrDocument(IrDocument.VERSION, IrStatus.READY, null, List.of(), null, List.of(query), view(), List.copyOf(SALES_CONTEXT));

        assertThatThrownBy(() -> pipelines.create(answer(forged)).run("고객 연락처별 매출", SALES_CONTEXT, user(owner, Set.of(first)), new Cancellation()))
                .isInstanceOf(EngineException.class);
    }

    @Test void staffWithoutSalesGrantCannotReadPayments() {
        long staff = person("EMPLOYEE");
        member(staff, first, "ai:read");
        var outcome = new AtomicReference<com.openstudy.domainlens.query.QueryPipeline.Outcome>();

        try {
            outcome.set(pipelines.create(answer(salesByBranch()))
                    .run("지점별 결제 금액 합계", SALES_CONTEXT, user(staff, Set.of(first)), new Cancellation()));
        } catch (EngineException denied) {
            return;
        }
        // Without sales:read the payment context never reaches the model: the request stops before any query runs.
        assertThat(outcome.get().ir().status()).isNotEqualTo(IrStatus.READY);
        assertThat(outcome.get().plans()).isEmpty();
        assertThat(outcome.get().result().datasets()).isEmpty();
    }

    // --- recorded answer ---

    static IrDocument salesByBranch() {
        var query = new QuerySpec("sales", "payment", "payment", QuerySpec.Mode.AGGREGATE,
                List.of(new QuerySpec.Selection("payment.branch_id", "branch_id")),
                List.of(new QuerySpec.Selection("sales.net_sales_krw", "net_sales")),
                null, List.of(new QuerySpec.Order("branch_id", QuerySpec.Direction.ASC)), new QuerySpec.Page(0, 50), 50, null);
        return new IrDocument(IrDocument.VERSION, IrStatus.READY, null, List.of(), null, List.of(query), view(), List.copyOf(SALES_CONTEXT));
    }

    static ViewSpec view() {
        var table = new ViewSpec.Component("table", new ViewSpec.Ref("DataTable", "1.0.0"), "sales",
                Map.of("columns", List.of("branch_id", "net_sales")), Map.of(), List.of());
        return new ViewSpec("openstudy-ui-1.0.0", List.of(table), Layout.stack(List.of(Layout.leaf("table"))));
    }

    static ModelGateway answer(IrDocument ir) {
        return (ask, refs) -> ir;
    }

    // --- fixtures ---

    UserContext user(long employee, Set<Long> branches) {
        return actors.resolveByEmployeeId(employee).toUserContext("all", branches);
    }

    long customer() {
        String no = "ENG-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO customers(member_no,name) VALUES(?,'엔진 고객')", no);
        long id = jdbc.queryForObject("SELECT id FROM customers WHERE member_no=?", Long.class, no);
        customerRows.add(id);
        return id;
    }

    void pay(long customer, long branch, long amount, String status) {
        jdbc.update("INSERT INTO payments(customer_id,branch_id,purpose,method,amount_krw,status,idempotency_key,issued_pass_quantity,approved_at,failed_at,created_by) "
                        + "VALUES(?,?,'LOCKER','CASH',?,?,?,0,?,?,?)",
                customer, branch, amount, status, "ENG-" + UUID.randomUUID(),
                "APPROVED".equals(status) ? java.sql.Timestamp.from(java.time.Instant.now()) : null,
                "FAILED".equals(status) ? java.sql.Timestamp.from(java.time.Instant.now()) : null, owner);
    }

    /**
     * The catalog's sales.net_sales_krw has no default filter yet, so it sums every payment row of the branch,
     * including failed ones. Matching the structured sales API is #232 stage 1 (catalog) work.
     */
    long rawAmountSum(long branch) {
        return count("SELECT COALESCE(SUM(amount_krw),0) FROM payments WHERE branch_id=?", branch);
    }

    static Map<Long, Long> sums(List<?> rows) {
        var result = new java.util.HashMap<Long, Long>();
        for (Object row : rows) {
            var cells = (Map<?, ?>) row;
            result.put(((Number) cells.get("branch_id")).longValue(), ((Number) cells.get("net_sales")).longValue());
        }
        return result;
    }
}
