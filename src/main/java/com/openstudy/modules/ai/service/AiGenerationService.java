package com.openstudy.modules.ai.service;

import com.openstudy.domainlens.api.error.EngineException;
import com.openstudy.domainlens.api.ir.Filter;
import com.openstudy.domainlens.api.ir.IrStatus;
import com.openstudy.domainlens.api.ir.QuerySpec;
import com.openstudy.domainlens.api.ir.ResultEnvelope;
import com.openstudy.domainlens.api.ir.ViewSpec;
import com.openstudy.domainlens.api.prompt.ModelUsage;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.api.work.Cancellation;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * #233: the DomainLens generation path behind the AI chat. A question no registered analysis answers is planned by the
 * live model, compiled and validated by the server, and run under the requester's policies. A saved result runs again
 * from its saved queries without the model, with its relative period read again from the server clock.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGenerationService {
    public static final String RESULT_CATEGORY = "GENERATED_EXECUTION";

    private final OpenStudyQueryPipelineFactory pipelines;
    private final OpenStudyGenerationModel model;
    private final OpenStudyIntentAnalyzer intents;
    private final SemanticRegistry registry;

    public sealed interface Result permits Completed, Declined, Failed {}

    /** The model's queries ran: the saved queries and view, the result, and the model's usage. */
    public record Completed(List<QuerySpec> queries, ViewSpec view, ResultEnvelope envelope, String domain,
            List<ModelUsage> usage, long durationMs) implements Result {}

    /** The generation path could not answer either (unknown metric, ungrounded selection, …): still unsupported. */
    public record Declined(String reason, long durationMs) implements Result {}

    /** The request failed for a reason the requester should see (permission, timeout, budget, model failure). */
    public record Failed(int httpStatus, String code, String message, long durationMs) implements Result {}

    public boolean available() {
        return model.gateway().isPresent();
    }

    /** The general metric the server reads in the question, if any; it decides the permission checked before the model. */
    public Optional<SemanticRegistry.Metric> metric(String question) {
        return intents.analyze(question, null).metrics().stream().findFirst().map(id -> registry.metrics().get(id));
    }

    public static boolean sales(SemanticRegistry.Metric metric) {
        return "payment".equals(metric.source()) || "refund".equals(metric.source());
    }

    public Result generate(String question, UserContext user) {
        var gateway = model.gateway().orElseThrow(() -> new IllegalStateException("No generation model"));
        long started = System.nanoTime();
        try {
            var outcome = pipelines.create(gateway).run(question, user, new Cancellation(), pipelines.retrieval());
            long ms = elapsed(started);
            if (outcome.ir().status() != IrStatus.READY || !"COMPLETED".equals(outcome.result().status())) {
                String reason = outcome.ir().reasonCode() == null ? outcome.ir().status().name() : outcome.ir().reasonCode().name();
                return new Declined(reason, ms);
            }
            var queries = outcome.ir().queries();
            return new Completed(queries, outcome.ir().view(), outcome.result(), domain(queries), outcome.usage(), ms);
        } catch (EngineException ex) {
            log.atInfo().addKeyValue("engineCode", ex.code()).log("AI generation stopped: {} {}", ex.code(), ex.getMessage());
            return failure(ex, elapsed(started));
        }
    }

    /** Saved generated queries run again without the model; a relative period ("이번 달") follows the clock as of now. */
    public ResultEnvelope rerun(List<QuerySpec> saved, ViewSpec view, String question, UserContext user) {
        return pipelines.execute(saved.stream().map(query -> rebase(query, question)).toList(), view, user);
    }

    /** The query with its period on the metric's time field replaced by the server's current reading of the question. */
    QuerySpec rebase(QuerySpec query, String question) {
        var period = intents.analyze(question, null).period();
        if (period == null || query.mode() != QuerySpec.Mode.AGGREGATE) return query;
        var periodLeaves = new ArrayList<Filter.Leaf>();
        if (!conjunction(period, periodLeaves)) return query;
        var fields = new HashSet<String>();
        periodLeaves.forEach(leaf -> fields.add(leaf.field()));
        var kept = new ArrayList<Filter.Leaf>();
        if (query.filter() != null && !conjunction(query.filter(), kept)) return query;
        if (kept.stream().noneMatch(leaf -> fields.contains(leaf.field()))) return query;
        var children = new ArrayList<Filter>();
        kept.stream().filter(leaf -> !fields.contains(leaf.field())).forEach(children::add);
        children.addAll(periodLeaves);
        return new QuerySpec(query.id(), query.source(), query.grain(), query.mode(), query.dimensions(), query.metrics(),
                new Filter.Group(Filter.Junction.AND, children), query.orderBy(), query.pagination(), query.limit(), query.analysisId());
    }

    private static boolean conjunction(Filter filter, List<Filter.Leaf> leaves) {
        if (filter instanceof Filter.Leaf leaf) { leaves.add(leaf); return true; }
        return filter instanceof Filter.Group group && group.junction() == Filter.Junction.AND
                && group.children().stream().allMatch(child -> conjunction(child, leaves));
    }

    /** The permission domain of the queries: payments and refunds are sales. */
    static String domain(List<QuerySpec> queries) {
        boolean sales = queries.stream().anyMatch(q -> "payment".equals(q.source()) || "refund".equals(q.source()));
        return sales ? "sales" : queries.isEmpty() ? "generated" : queries.getFirst().source();
    }

    static Result failure(EngineException ex, long ms) {
        return switch (ex.code()) {
            case SCOPE_DENIED, PII_DENIED -> new Failed(403, "FORBIDDEN", "현재 권한과 지점 범위로는 이 질문을 조회할 수 없어요.", ms);
            case MODEL_TIMEOUT, QUERY_TIMEOUT -> new Failed(504, "AI_TIMEOUT", "AI 조회 시간이 초과됐어요. 기간이나 범위를 좁혀 다시 질문해 주세요.", ms);
            case BUDGET_EXCEEDED -> new Failed(422, "AI_BUDGET_EXCEEDED", "질문이나 결과가 처리 한도를 넘었어요. 범위를 좁혀 다시 질문해 주세요.", ms);
            case NOT_READY -> new Failed(503, "AI_NOT_READY", "AI 분석 카탈로그가 준비되지 않아 지금은 질문을 실행할 수 없어요.", ms);
            case CANCELED -> new Failed(409, "CANCELED", "질문이 취소됐어요.", ms);
            // M10 model boundary: a broken, refused or incomplete model reply ends as an ordinary answer, not a 5xx.
            case INTERNAL_ERROR -> new Failed(502, "AI_GENERATION_FAILED", "AI 조회를 완료하지 못했어요. 잠시 후 다시 시도해 주세요.", ms);
            default -> new Declined(ex.code().name(), ms);
        };
    }

    private static long elapsed(long started) {
        return Math.max(1L, Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    /** Whether a saved execution is a generated result: ran, no registered analysis, and its queries saved. */
    public static boolean generated(com.openstudy.modules.ai.entity.AiExecutionEntity execution) {
        return "READY".equals(execution.getStatus()) && execution.getAnalysisId() == null && execution.getQuerySpecJson() != null
                && RESULT_CATEGORY.equals(execution.getResultCategory());
    }
}
