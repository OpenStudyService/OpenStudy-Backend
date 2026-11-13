package com.openstudy.modules.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openstudy.domainlens.api.error.EngineException;
import com.openstudy.domainlens.api.ir.ContractJson;
import com.openstudy.domainlens.api.ir.IrDocument;
import com.openstudy.domainlens.api.ir.IrStatus;
import com.openstudy.domainlens.api.ir.Layout;
import com.openstudy.domainlens.api.ir.QuerySpec;
import com.openstudy.domainlens.api.ir.ViewSpec;
import com.openstudy.domainlens.api.planning.PlanningPrompt;
import com.openstudy.domainlens.api.planning.SemanticPlan;
import com.openstudy.domainlens.api.prompt.GenerationBudget;
import com.openstudy.domainlens.api.prompt.PromptPackage;
import com.openstudy.domainlens.api.spi.ModelGateway;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.api.work.Cancellation;
import com.openstudy.domainlens.query.QueryPipeline;
import com.openstudy.modules.ai.service.OpenStudyQueryPipelineFactory;
import com.openstudy.modules.ai.service.OpenStudyUserContextResolver;
import com.openstudy.support.database.MySqlIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
 * #232: the DomainLens generation path on the OpenStudy schema. A model answer (recorded IR, or a semantic plan the
 * live model returned) is grounded against the server's reading of the question, validated, planned under the
 * requester's policies, executed by JPA and bound to a view; the numbers come from the database, not from the model.
 */
@MySqlIntegrationTest
class DomainLensPipelineIntegrationTests extends AiTestSupport {
    static final String METRIC = "sales.approved_amount_krw";
    /** The approved context for approved payment amounts by branch name: the metric with every dependency. */
    static final Set<String> SALES_CONTEXT = Set.of(METRIC, "payment", "payment.amount_krw", "payment.status", "payment.paid_at",
            "payment.branch", "branch", "branch.name");

    @Autowired OpenStudyQueryPipelineFactory pipelines;
    @Autowired OpenStudyUserContextResolver actors;

    final List<Long> customerRows = new ArrayList<>();
    long owner, first, second;

    @BeforeEach void sales() {
        owner = person("OWNER");
        first = branch(owner, "엔진가" + UUID.randomUUID().toString().substring(0, 4) + "점");
        second = branch(owner, "엔진나" + UUID.randomUUID().toString().substring(0, 4) + "점");
        long customer = customer();
        Instant now = Instant.now();
        pay(customer, first, 30_000, "APPROVED", now);
        pay(customer, first, 12_000, "APPROVED", now);
        pay(customer, second, 50_000, "APPROVED", now);
        pay(customer, second, 99_000, "FAILED", now);
        // Approved long before this month: inside "all time", outside "이번 달".
        pay(customer, second, 7_000, "APPROVED", now.minus(Duration.ofDays(100)));
    }

    @AfterEach void cleanSales() {
        for (long id : customerRows) {
            jdbc.update("DELETE FROM payments WHERE customer_id=?", id);
            jdbc.update("DELETE FROM customers WHERE id=?", id);
        }
    }

    @Test void recordedAggregateRunsThroughValidationPlanningJpaAndBinding() {
        var outcome = pipelines.create(answer(approvedByBranch())).run("지점별 결제 금액 합계", SALES_CONTEXT, user(owner, Set.of(first, second)), new Cancellation());

        assertThat(outcome.ir().status()).isEqualTo(IrStatus.READY);
        assertThat(outcome.result().status()).isEqualTo("COMPLETED");
        assertThat(outcome.plans()).hasSize(1);
        assertThat(byName(outcome)).isEqualTo(Map.of(name(first), approved(first, false), name(second), approved(second, false)));
        assertThat(outcome.result().view()).isNotNull();
    }

    @Test void branchScopeLimitsTheRowsTheGeneratedQueryReads() {
        var outcome = pipelines.create(answer(approvedByBranch())).run("지점별 결제 금액 합계", SALES_CONTEXT, user(owner, Set.of(first)), new Cancellation());

        assertThat(byName(outcome)).isEqualTo(Map.of(name(first), approved(first, false)));
    }

    @Test void aRegisteredAnalysisMetricCannotRunAsARawAggregate() {
        // sales.net_sales_krw is the registered net sales analysis (refund-adjusted); its raw sum would be wrong.
        var query = new QuerySpec("sales", "payment", "payment", QuerySpec.Mode.AGGREGATE,
                List.of(new QuerySpec.Selection("payment.branch_id", "branch_id")),
                List.of(new QuerySpec.Selection("sales.net_sales_krw", "net_sales")),
                null, List.of(), new QuerySpec.Page(0, 50), 50, null);
        var context = Set.of("sales.net_sales_krw", "payment", "payment.amount_krw", "payment.branch_id", "payment.paid_at",
                "payment.status", "payment.branch", "branch", "branch.name", "refund", "refund.payment", "refund.refunded_at", "refund.amount_krw");
        var ir = new IrDocument(IrDocument.VERSION, IrStatus.READY, null, List.of(), null, List.of(query), table("branch_id", "net_sales"), List.copyOf(context));

        assertThatThrownBy(() -> pipelines.create(answer(ir)).run("지점별 순매출", context, user(owner, Set.of(first)), new Cancellation()))
                .isInstanceOf(EngineException.class);
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
                List.of(new QuerySpec.Selection(METRIC, "amount")),
                null, List.of(), new QuerySpec.Page(0, 50), 50, null);
        var forged = new IrDocument(IrDocument.VERSION, IrStatus.READY, null, List.of(), null, List.of(query), table("phone", "amount"), List.copyOf(SALES_CONTEXT));

        assertThatThrownBy(() -> pipelines.create(answer(forged)).run("고객 연락처별 결제 금액", SALES_CONTEXT, user(owner, Set.of(first)), new Cancellation()))
                .isInstanceOf(EngineException.class);
    }

    @Test void staffWithoutSalesGrantCannotReadPayments() {
        long staff = person("EMPLOYEE");
        member(staff, first, "ai:read");
        QueryPipeline.Outcome outcome;
        try {
            outcome = pipelines.create(answer(approvedByBranch())).run("지점별 결제 금액 합계", SALES_CONTEXT, user(staff, Set.of(first)), new Cancellation());
        } catch (EngineException denied) {
            return;
        }
        // Without sales:read the payment context never reaches the model: the request stops before any query runs.
        assertThat(outcome.ir().status()).isNotEqualTo(IrStatus.READY);
        assertThat(outcome.plans()).isEmpty();
        assertThat(outcome.result().datasets()).isEmpty();
    }

    @Test
    @EnabledIf("com.openstudy.domainlens.LocalAiFixtures#modelInstalled")
    void retrievalSuppliesTheContextTheRecordedAnswerUses() {
        var context = new AtomicReference<List<String>>();
        ModelGateway recording = (ask, refs) -> { context.set(refs); return approvedByBranch(); };

        var outcome = pipelines.create(recording).run("지점별 결제 금액 합계 알려줘", user(owner, Set.of(first, second)), new Cancellation(), pipelines.retrieval());

        assertThat(context.get()).containsAll(SALES_CONTEXT);
        assertThat(byName(outcome)).isEqualTo(Map.of(name(first), approved(first, false), name(second), approved(second, false)));
    }

    // --- replays of the plan qwen3.8-flash returned on 2026-11-13 ---

    /**
     * The live model's plan for this month by branch, with the period it computed (+09:00 offsets). The server
     * resolves "이번 달" itself (OpenStudyIntentAnalyzer); the model's range is the same instants, so it is grounded.
     */
    @Test
    @EnabledIf("com.openstudy.domainlens.LocalAiFixtures#modelInstalled")
    void livePlanWithItsOwnThisMonthRangeRunsForThisMonthOnly() throws Exception {
        var plan = withMetric(livePlan(), METRIC, true);

        var outcome = pipelines.create(replay(plan)).run("이번 달 지점별 결제 금액 합계 보여줘", user(owner, Set.of(first, second)), new Cancellation(), pipelines.retrieval());

        assertThat(outcome.ir().status()).as(ContractJson.write(outcome.ir())).isEqualTo(IrStatus.READY);
        assertThat(byName(outcome)).isEqualTo(Map.of(name(first), approved(first, true), name(second), approved(second, true)));
    }

    /** A plan without a filter takes the server-resolved period: the model need not compute dates at all. */
    @Test
    @EnabledIf("com.openstudy.domainlens.LocalAiFixtures#modelInstalled")
    void livePlanWithoutAFilterUsesTheServerResolvedPeriod() throws Exception {
        var plan = withMetric(livePlan(), METRIC, false);

        var outcome = pipelines.create(replay(plan)).run("이번 달 지점별 결제 금액 합계 보여줘", user(owner, Set.of(first, second)), new Cancellation(), pipelines.retrieval());

        assertThat(outcome.ir().status()).as(ContractJson.write(outcome.ir())).isEqualTo(IrStatus.READY);
        assertThat(byName(outcome)).isEqualTo(Map.of(name(first), approved(first, true), name(second), approved(second, true)));
    }

    /** The plan exactly as recorded picks the registered net sales metric; the server refuses to run it raw. */
    @Test
    @EnabledIf("com.openstudy.domainlens.LocalAiFixtures#modelInstalled")
    void livePlanOnTheRegisteredNetSalesMetricDoesNotRun() throws Exception {
        QueryPipeline.Outcome outcome;
        try {
            outcome = pipelines.create(replay(livePlan())).run("이번 달 지점별 매출 합계 보여줘", user(owner, Set.of(first, second)), new Cancellation(), pipelines.retrieval());
        } catch (EngineException refused) {
            return;
        }
        assertThat(outcome.ir().status()).isNotEqualTo(IrStatus.READY);
        assertThat(outcome.plans()).isEmpty();
    }

    /**
     * One live Qwen round trip through the same pipeline, opt-in only (OPENSTUDY_AI_LIVE_TESTS=true with QWEN_API_KEY,
     * QWEN_BASE_URL and QWEN_MODEL set): it spends tokens on the configured account. Both model output styles run
     * (direct IR and a semantic plan compiled by the server); the raw plan, prompt size, compiled IR and outcome are
     * written to build/live-qwen.txt for the development log.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "OPENSTUDY_AI_LIVE_TESTS", matches = "true")
    void liveQwenGeneratesAQueryTheServerValidatesAndRuns() throws Exception {
        var report = new StringBuilder();
        String question = System.getenv().getOrDefault("OPENSTUDY_AI_LIVE_QUESTION", "이번 달 지점별 결제 금액 합계 보여줘");
        report.append("question=").append(question).append(System.lineSeparator());
        for (boolean planning : List.of(false, true)) {
            var gateway = com.openstudy.domainlens.ai.LiveModelGatewayFactory.create(System.getenv("QWEN_BASE_URL"),
                    System.getenv("QWEN_API_KEY"), System.getenv("QWEN_MODEL"), Duration.ofSeconds(60),
                    com.openstudy.domainlens.api.prompt.PromptLimits.defaults(), false).planning(planning);
            long started = System.nanoTime();
            report.append("== planning=").append(planning).append(System.lineSeparator());
            try {
                var outcome = pipelines.create(recordingPlans(gateway, report)).planningObservations(
                        prompt -> report.append("promptBytes=").append(ContractJson.write(prompt).getBytes(StandardCharsets.UTF_8).length).append(System.lineSeparator()),
                        compiled -> report.append("compiled=").append(ContractJson.write(compiled)).append(System.lineSeparator())
                ).run(question, user(owner, Set.of(first, second)), new Cancellation(), pipelines.retrieval());
                report.append("ir=").append(ContractJson.write(outcome.ir())).append(System.lineSeparator())
                        .append("status=").append(outcome.result().status()).append(System.lineSeparator())
                        .append("rows=").append(outcome.result().datasets().stream().map(d -> d.rows().toString()).toList()).append(System.lineSeparator())
                        .append("expected(this month)=").append(Map.of(name(first), approved(first, true), name(second), approved(second, true))).append(System.lineSeparator())
                        .append("usage=").append(outcome.usage()).append(System.lineSeparator());
            } catch (EngineException ex) {
                report.append("error=").append(ex.code()).append(' ').append(ex.getMessage()).append(System.lineSeparator());
            }
            report.append("ms=").append((System.nanoTime() - started) / 1_000_000).append(System.lineSeparator());
        }
        java.nio.file.Files.writeString(java.nio.file.Path.of("build/live-qwen.txt"), report.toString());
        assertThat(report).contains("planning=true");
    }

    // --- answers ---

    static IrDocument approvedByBranch() {
        var query = new QuerySpec("sales", "payment", "payment", QuerySpec.Mode.AGGREGATE,
                List.of(new QuerySpec.Selection("branch.name", "branch_name")),
                List.of(new QuerySpec.Selection(METRIC, "amount")),
                null, List.of(new QuerySpec.Order("branch_name", QuerySpec.Direction.ASC)), new QuerySpec.Page(0, 50), 50, null);
        return new IrDocument(IrDocument.VERSION, IrStatus.READY, null, List.of(), null, List.of(query), table("branch_name", "amount"), List.copyOf(SALES_CONTEXT));
    }

    static ViewSpec table(String... columns) {
        var table = new ViewSpec.Component("table", new ViewSpec.Ref("DataTable", "1.0.0"), "sales",
                Map.of("columns", List.of(columns)), Map.of(), List.of());
        return new ViewSpec("openstudy-ui-1.0.0", List.of(table), Layout.stack(List.of(Layout.leaf("table"))));
    }

    static ModelGateway answer(IrDocument ir) {
        return (ask, refs) -> ir;
    }

    SemanticPlan livePlan() throws java.io.IOException {
        var json = new String(getClass().getResourceAsStream("/domainlens/live-plan-branch-sales.json").readAllBytes(), StandardCharsets.UTF_8);
        return ContractJson.read(json, SemanticPlan.class);
    }

    /** The recorded plan with another metric, keeping or dropping the model's own period filter. */
    static SemanticPlan withMetric(SemanticPlan live, String metric, boolean keepFilter) {
        var r = live.requests().getFirst();
        var evidence = r.evidence().stream()
                .filter(e -> keepFilter || !e.slot().equals("filter"))
                .map(e -> e.slot().equals("sales.net_sales_krw") ? new SemanticPlan.Evidence(metric, e.origin(), e.quote()) : e)
                .map(e -> new SemanticPlan.Evidence(e.slot(), e.origin(), "이번 달 지점별 결제 금액 합계 보여줘"))
                .toList();
        var request = new SemanticPlan.Request(r.kind(), r.source(), r.analysisId(), List.of(metric), r.dimensions(),
                keepFilter ? r.filter() : null, r.order(), r.page(), r.size(), r.limit(), r.total(), r.presentations(), evidence, r.formulas());
        return new SemanticPlan(live.version(), live.status(), live.reason(), live.options(), List.of(request), live.unresolved(), live.reasonCode());
    }

    static ModelGateway replay(SemanticPlan plan) {
        return new ModelGateway() {
            @Override public boolean supportsPlanning() { return true; }
            @Override public SemanticPlan plan(PlanningPrompt prompt, GenerationBudget budget, SemanticPlan previous, String failure) { return plan; }
            @Override public IrDocument generate(String ask, List<String> refs) { throw new AssertionError("planning only"); }
        };
    }

    /** Delegates to the live gateway and writes each raw semantic plan the model returns, before the server compiles it. */
    static ModelGateway recordingPlans(ModelGateway live, StringBuilder report) {
        return new ModelGateway() {
            @Override public boolean supportsPlanning() { return live.supportsPlanning(); }
            @Override public boolean supportsRepair() { return live.supportsRepair(); }
            @Override public Duration requestTimeout() { return live.requestTimeout(); }
            @Override public SemanticPlan plan(PlanningPrompt prompt, GenerationBudget budget, SemanticPlan previous, String failure) {
                var plan = live.plan(prompt, budget, previous, failure);
                report.append("plan=").append(ContractJson.write(plan)).append(System.lineSeparator());
                return plan;
            }
            @Override public IrDocument generate(PromptPackage prompt, GenerationBudget budget) { return live.generate(prompt, budget); }
            @Override public IrDocument repair(PromptPackage prompt, IrDocument previous, String failure, GenerationBudget budget) {
                return live.repair(prompt, previous, failure, budget);
            }
            @Override public IrDocument generate(String ask, List<String> contextRefs) { return live.generate(ask, contextRefs); }
        };
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

    void pay(long customer, long branch, long amount, String status, Instant at) {
        jdbc.update("INSERT INTO payments(customer_id,branch_id,purpose,method,amount_krw,status,idempotency_key,issued_pass_quantity,approved_at,failed_at,created_by) "
                        + "VALUES(?,?,'LOCKER','CASH',?,?,?,0,?,?,?)",
                customer, branch, amount, status, "ENG-" + UUID.randomUUID(),
                "APPROVED".equals(status) ? Timestamp.from(at) : null, "FAILED".equals(status) ? Timestamp.from(at) : null, owner);
    }

    /** Approved payment amounts of the branch, in this KST calendar month only or in all time. */
    long approved(long branch, boolean thisMonth) {
        var month = java.time.YearMonth.now(java.time.ZoneId.of("Asia/Seoul"));
        var from = thisMonth ? month.atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant() : Instant.EPOCH;
        var to = thisMonth ? month.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant() : Instant.parse("2100-01-01T00:00:00Z");
        return count("SELECT COALESCE(SUM(amount_krw),0) FROM payments WHERE branch_id=? AND status='APPROVED' AND approved_at>=? AND approved_at<?",
                branch, Timestamp.from(from), Timestamp.from(to));
    }

    String name(long branch) {
        return jdbc.queryForObject("SELECT name FROM branches WHERE id=?", String.class, branch);
    }

    static Map<String, Long> byName(QueryPipeline.Outcome outcome) {
        var result = new HashMap<String, Long>();
        for (var row : outcome.result().datasets().getFirst().rows()) {
            var values = new ArrayList<>(row.values());
            result.put(values.get(0).toString(), ((Number) values.get(1)).longValue());
        }
        return result;
    }
}
