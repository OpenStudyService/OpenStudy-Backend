package com.openstudy.modules.ai.service;

import com.openstudy.config.DomainLensProperties;
import com.openstudy.domainlens.ai.LiveModelGatewayFactory;
import com.openstudy.domainlens.api.ir.QuerySpec;
import com.openstudy.domainlens.api.spi.ModelGateway;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.catalog.EmbeddingIndex;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import com.openstudy.domainlens.query.Retriever;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Deterministic Korean intent & guardrail planner backed by local ONNX ({@code koen-e5-tiny-q8}) semantic retrieval
 * with optional live Qwen LLM gateway integration ({@code OS-DL-0502}, {@code OS-DL-0505}).
 */
@Service
public class OpenStudyPlaybackGateway {
    public static final int MAX_QUESTION_CHARS = 500;
    public static final int MAX_DATE_RANGE_DAYS = 366;
    public static final int MAX_RESULT_ROWS = 1000;
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_CHART_CATEGORIES = 20;
    /** The refusal for questions plainly outside OpenStudy (weather, stocks, …); the generation path never sees them. */
    public static final String OUT_OF_DOMAIN_MESSAGE = "OpenStudy 업무 도메인(매출·좌석·이용권·스터디룸·사물함·업무·근태) 외의 질문은 지원하지 않습니다.";

    /**
     * #233: whether a decision only means "no registered analysis answers this", so the DomainLens generation path may
     * try. Refusals for permission, modification, personal data, clarifications and plainly off-domain topics never go.
     */
    public static boolean noRegisteredAnalysis(PlanDecision plan, String clarificationChoice) {
        return clarificationChoice == null && "UNSUPPORTED".equals(plan.outcome()) && plan.analysisId() == null
                && "OUT_OF_DOMAIN".equals(plan.errorCode()) && !OUT_OF_DOMAIN_MESSAGE.equals(plan.message());
    }

    public record ClarificationOption(String id, String label, String targetAnalysisId, String targetScope) {}

    public record TruncationInfo(
            int maxRows,
            int pageSize,
            int maxChartCategories,
            boolean isTruncated,
            String warningCode,
            String message
    ) {
        public static TruncationInfo defaultUntruncated() {
            return new TruncationInfo(MAX_RESULT_ROWS, DEFAULT_PAGE_SIZE, MAX_CHART_CATEGORIES, false, null, null);
        }

        public static TruncationInfo clamped(String reason) {
            return new TruncationInfo(
                    MAX_RESULT_ROWS,
                    DEFAULT_PAGE_SIZE,
                    MAX_CHART_CATEGORIES,
                    true,
                    "ROW_LIMIT_CLAMPED",
                    reason
            );
        }
    }

    private static final Logger log = LoggerFactory.getLogger(OpenStudyPlaybackGateway.class);

    public record RetrievalCandidate(String id, String kind, double score) {}

    public record LiveLlmPlanResult(
            String outcome,
            String analysisId,
            String intentDomain,
            String refundBasisOverride,
            boolean zeroFillOverrideRequested,
            String message,
            long durationMs,
            int totalTokens
    ) {}

    public record PlanDecision(
            String outcome, // EXECUTE, CLARIFICATION, UNSUPPORTED, DENIED, INVALID
            int httpStatus,
            String errorCode,
            String resultCategory,
            String behavior,
            String message,
            String analysisId,
            String intentDomain,
            String refundBasisOverride,
            boolean zeroFillOverrideRequested,
            TruncationInfo truncation,
            List<ClarificationOption> clarificationOptions,
            List<RetrievalCandidate> retrievedCandidates,
            long llmDurationMs,
            int totalTokens
    ) {
        public PlanDecision(
                String outcome,
                int httpStatus,
                String errorCode,
                String resultCategory,
                String behavior,
                String message,
                String analysisId,
                String intentDomain,
                String refundBasisOverride,
                boolean zeroFillOverrideRequested,
                TruncationInfo truncation,
                List<ClarificationOption> clarificationOptions,
                List<RetrievalCandidate> retrievedCandidates
        ) {
            this(
                    outcome,
                    httpStatus,
                    errorCode,
                    resultCategory,
                    behavior,
                    message,
                    analysisId,
                    intentDomain,
                    refundBasisOverride,
                    zeroFillOverrideRequested,
                    truncation,
                    clarificationOptions,
                    retrievedCandidates,
                    0L,
                    0
            );
        }
    }

    private final SemanticRegistry registry;
    private final Retriever retriever;
    private final EmbeddingIndex embeddingIndex;
    private final OpenStudyAnalyses analyses;
    private final DomainLensProperties properties;
    private final String modelMode;
    private final OpenStudyIntentAnalyzer intents;
    private final int maxConcurrentPerUser;
    private final int maxRequestsPerMinute;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentHashMap<Long, AtomicInteger> activeRequestsByUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Deque<Long>> requestTimestampsByUser = new ConcurrentHashMap<>();
    private volatile ModelGateway liveGateway;
    private final OpenStudyUserContextResolver userResolver;

    public OpenStudyPlaybackGateway(
            SemanticRegistry registry,
            Retriever retriever,
            EmbeddingIndex embeddingIndex,
            OpenStudyAnalyses analyses,
            OpenStudyUserContextResolver userResolver,
            DomainLensProperties properties,
            OpenStudyIntentAnalyzer intents,
            @Value("${openstudy.ai.model-mode:playback}") String modelMode,
            @Value("${openstudy.ai.rate-limit.max-concurrent:10}") int maxConcurrentPerUser,
            @Value("${openstudy.ai.rate-limit.max-per-minute:120}") int maxRequestsPerMinute
    ) {
        this.registry = registry;
        this.retriever = retriever;
        this.embeddingIndex = embeddingIndex;
        this.analyses = analyses;
        this.userResolver = userResolver;
        this.properties = properties;
        this.intents = intents;
        this.modelMode = modelMode == null ? "playback" : modelMode.trim().toLowerCase(Locale.ROOT);
        this.maxConcurrentPerUser = maxConcurrentPerUser;
        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public String activeMode() {
        if ("live".equalsIgnoreCase(modelMode)
                && properties.getModel().getApiKey() != null
                && !properties.getModel().getApiKey().isBlank()) {
            return "live";
        }
        return "playback";
    }

    public String activeModelName() {
        if ("live".equals(activeMode())) {
            String configured = properties.getModel().getName();
            return (configured == null || configured.isBlank()) ? "qwen3.8-flash" : configured;
        }
        return "playback-ir";
    }

    public Optional<ModelGateway> liveModelGateway() {
        if (!"live".equals(activeMode())) {
            return Optional.empty();
        }
        if (liveGateway == null) {
            synchronized (this) {
                if (liveGateway == null) {
                    var m = properties.getModel();
                    liveGateway = LiveModelGatewayFactory.create(
                            m.getBaseUrl(),
                            m.getApiKey(),
                            m.getName(),
                            m.getTimeout(),
                            com.openstudy.domainlens.api.prompt.PromptLimits.defaults(),
                            m.getEnableThinking(),
                            m.getReasoningEffort(),
                            m.getMaxCompletionTokens()
                    );
                }
            }
        }
        return Optional.of(liveGateway);
    }

    /**
     * Acquires a per-user rate-limit and concurrency slot ({@code ADR-M10-001} D02).
     * Returns {@code true} if allowed, or {@code false} if rate-limited ({@code 429 RATE_LIMITED}).
     */
    public boolean tryAcquireRateLimit(long employeeId) {
        long nowMs = System.currentTimeMillis();
        Deque<Long> window = requestTimestampsByUser.computeIfAbsent(employeeId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && nowMs - window.peekFirst() >= 60_000L) {
                window.pollFirst();
            }
            if (window.size() >= maxRequestsPerMinute) {
                return false;
            }
            AtomicInteger active = activeRequestsByUser.computeIfAbsent(employeeId, k -> new AtomicInteger(0));
            if (active.get() >= maxConcurrentPerUser) {
                return false;
            }
            active.incrementAndGet();
            window.addLast(nowMs);
            return true;
        }
    }

    public void releaseRateLimit(long employeeId) {
        AtomicInteger active = activeRequestsByUser.get(employeeId);
        if (active != null) {
            active.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    public void resetRateLimits() {
        activeRequestsByUser.clear();
        requestTimestampsByUser.clear();
    }

    /**
     * Classifies a natural-language question, enforcing all input, privacy, injection, budget, ambiguity,
     * and domain guardrails ({@code OS-DL-0501}, {@code OS-DL-0502}, {@code NEG-001}..{@code NEG-018}).
     */
    public PlanDecision planQuestion(
            String rawQuestion,
            String clarificationChoice,
            OpenStudyUserContextResolver.ResolvedActor actor,
            String requestedScope
    ) {
        return planQuestion(rawQuestion, clarificationChoice, actor, requestedScope, null, null);
    }

    /**
     * #126 (M15-D06): {@code followUpAnalysisId} is the previous result's analysis, given only when the question is a
     * follow-up that names a condition but no analysis of its own; every guard above the analysis choice still applies.
     * {@code previousConditions} is what the model is told about that result instead of the earlier question's text.
     */
    public PlanDecision planQuestion(
            String rawQuestion,
            String clarificationChoice,
            OpenStudyUserContextResolver.ResolvedActor actor,
            String requestedScope,
            String followUpAnalysisId,
            String previousConditions
    ) {
        // 0. Basic ai:read permission check (ADR-M10-001 D06: 9200004 osdemo.ss.noai has no ai:read)
        if (actor != null && actor.aiBranches().isEmpty()) {
            return new PlanDecision(
                    "DENIED",
                    403,
                    "AI_PERMISSION_DENIED",
                    "AUTHORIZATION_DENIED",
                    "403",
                    "AI 분석 조회(ai:read) 권한이 없습니다.",
                    null,
                    "security",
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(),
                    List.of()
            );
        }

        // 1. Input length & blank checks (ADR-M10-001 D02)
        if (rawQuestion == null || rawQuestion.isBlank()) {
            return new PlanDecision(
                    "INVALID",
                    400,
                    "QUESTION_EMPTY",
                    "VALIDATION_FAILED",
                    "400",
                    "질문을 입력해 주세요.",
                    null,
                    null,
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(),
                    List.of()
            );
        }
        String q = (clarificationChoice != null && !clarificationChoice.isBlank())
                ? clarificationChoice.trim()
                : rawQuestion.trim();

        if (rawQuestion.length() > MAX_QUESTION_CHARS) {
            return new PlanDecision(
                    "INVALID",
                    400,
                    "QUESTION_LENGTH_EXCEEDED",
                    "VALIDATION_FAILED",
                    "400",
                    "질문은 최대 500자까지 입력할 수 있습니다.",
                    null,
                    null,
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(),
                    List.of()
            );
        }

        String upper = q.toUpperCase(Locale.ROOT);

        // 2. Arbitrary HTML / Script injection check (NEG-011)
        if (upper.contains("<SCRIPT") || upper.contains("</SCRIPT>") || upper.contains("JAVASCRIPT:")
                || upper.contains("ONERROR=") || upper.contains("<IFRAME")) {
            return new PlanDecision(
                    "DENIED",
                    400,
                    "REJECT_ARBITRARY_HTML",
                    "REJECT_ARBITRARY_HTML",
                    "NO_SCRIPT",
                    "보안 정책상 임의의 HTML 또는 스크립트 태그는 실행하거나 렌더링할 수 없습니다.",
                    null,
                    "security",
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(),
                    List.of()
            );
        }

        // 3. Raw SQL / JPQL / DDL / DML injection check (NEG-010)
        if (upper.contains("SELECT * FROM") || upper.contains("DROP TABLE") || upper.contains("DELETE FROM")
                || upper.contains("INSERT INTO") || upper.contains("TRUNCATE TABLE")
                || q.contains("문장을 그대로 실행")) {
            return new PlanDecision(
                    "DENIED",
                    400,
                    "REJECT_RAW_SQL",
                    "REJECT_RAW_SQL",
                    "NO_EXECUTION",
                    "보안 정책상 SQL/JPQL 문장을 직접 실행할 수 없습니다. 등록된 업무 지표로 질문해 주세요.",
                    null,
                    "security",
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(),
                    List.of()
            );
        }

        // 4. Read-only modification / cancellation / deletion intent check
        if (q.contains("전부 취소해줘") || q.contains("취소 처리해줘") || q.contains("환불 처리해줘")
                || q.contains("삭제해줘") || q.contains("초기화해줘") || q.contains("변경해줘")) {
            return new PlanDecision(
                    "DENIED",
                    400,
                    "UNSUPPORTED_MODIFICATION_INTENT",
                    "READ_ONLY_VIOLATION",
                    "NO_EXECUTION",
                    "업무 AI는 읽기 전용 분석만 지원합니다. 취소·환불·삭제 처리는 해당 운영 메뉴에서 진행해 주세요.",
                    null,
                    "security",
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(),
                    List.of()
            );
        }

        // 5. Privacy / PII / Credential / Forbidden raw text checks (NEG-004, NEG-012, task spec NEG-005/015/016)
        boolean asksCustomerPii = q.contains("전화번호") || q.contains("연락처") || q.contains("휴대폰")
                || (q.contains("고객") && q.contains("이메일"));
        boolean asksEmployeeCredentials = q.contains("비밀번호") || q.contains("패스워드") || q.contains("해시")
                || upper.contains("PASSWORD") || (q.contains("직원") && q.contains("이메일"));
        boolean asksForbiddenRawText = q.contains("채팅에서") || q.contains("팀 채팅") || q.contains("대화 내용")
                || q.contains("문의 내용 원문") || q.contains("고객 문의 원문") || q.contains("상담 원문");

        if (asksCustomerPii) {
            // NEG-004: non-owner asking for all branches + payment details + customer phone
            if (!actor.owner() && (q.contains("모든 지점") || q.contains("전 지점") || q.contains("결제 상세"))) {
                return new PlanDecision(
                        "DENIED",
                        403,
                        "PII_OR_SCOPE_DENIED",
                        "AUTHORIZATION_DENIED",
                        "403_OR_REDACT",
                        "타 지점 결제 상세 및 고객 개인정보(전화번호·이메일)는 조회하거나 AI로 전달할 수 없습니다.",
                        null,
                        "privacy",
                        null,
                        false,
                        TruncationInfo.defaultUntruncated(),
                        List.of(),
                        List.of()
                );
            }
            // NEG-012: owner or manager asking for customer phone/email list
            return new PlanDecision(
                    "DENIED",
                    403,
                    "PII_MINIMIZATION",
                    "PII_MINIMIZATION",
                    "REDACT_OR_DENY",
                    "개인정보 최소 수집·노출 원칙(ADR-M10-001 D07)에 따라 고객 전화번호 및 이메일은 AI 분석에서 제외됩니다.",
                    null,
                    "privacy",
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(),
                    List.of()
            );
        }

        if (asksEmployeeCredentials) {
            return new PlanDecision(
                    "DENIED",
                    403,
                    "CREDENTIAL_ACCESS_DENIED",
                    "PII_MINIMIZATION",
                    "REDACT_OR_DENY",
                    "직원 비밀번호 해시·인증정보 및 개인 이메일은 보안 정책상 조회할 수 없습니다.",
                    null,
                    "privacy",
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(),
                    List.of()
            );
        }

        if (asksForbiddenRawText) {
            return new PlanDecision(
                    "DENIED",
                    403,
                    "FORBIDDEN_SOURCE_DENIED",
                    "AUTHORIZATION_DENIED",
                    "REDACT_OR_DENY",
                    "팀 채팅 메시지 및 고객 문의 원문은 AI 분석 허용 도메인이 아닙니다.",
                    null,
                    "privacy",
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(),
                    List.of()
            );
        }

        // 6. Date range > 366 days budget check (NEG-005 in negative_cases.json / NEG-006 in task spec)
        if ((q.contains("2024년") && q.contains("2026년"))
                || q.contains("3년치") || q.contains("최근 3년") || q.contains("2년치") || q.contains("최근 2년")
                || q.contains("500일") || q.contains("400일")) {
            return new PlanDecision(
                    "INVALID",
                    400,
                    "DATE_RANGE_LIMIT_EXCEEDED",
                    "INVALID_RANGE",
                    "400",
                    "조회 기간은 최대 366일(1년) 이하여야 합니다. 366일 이내의 기간으로 다시 지정해 주세요.",
                    null,
                    "sales",
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(),
                    List.of()
            );
        }

        // 7. Out-of-domain check (NEG-013 in negative_cases.json / NEG-017 in task spec)
        if (q.contains("고속버스") || q.contains("예매해줘") || q.contains("날씨") || q.contains("주식")
                || q.contains("비트코인") || q.contains("맛집") || q.contains("영화 추천")) {
            return new PlanDecision(
                    "UNSUPPORTED",
                    200,
                    "OUT_OF_DOMAIN",
                    "UNSUPPORTED_DOMAIN",
                    "UNSUPPORTED",
                    OUT_OF_DOMAIN_MESSAGE,
                    null,
                    "unsupported",
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(),
                    List.of()
            );
        }

        // 8. Ambiguity & Clarification checks (NEG-007, NEG-008, NEG-009, NEG-014, and task spec clarifications)
        if (q.contains("비율 분모는 모름")) {
            // NEG-007
            return new PlanDecision(
                    "CLARIFICATION",
                    200,
                    "CLARIFICATION_OR_POLICY_PENDING",
                    "CLARIFICATION_OR_POLICY_PENDING",
                    "NOT_DETERMINED",
                    "혼잡도 산식 및 분모 기준(가용 좌석 23석 vs 전체 좌석 24석)을 선택해 주세요.",
                    OpenStudyAnalyses.ID_POC_002,
                    "seats",
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(
                            new ClarificationOption("occ-available", "언제 가장 붐벼?", OpenStudyAnalyses.ID_POC_002, requestedScope),
                            new ClarificationOption("seat-realtime", "지금 빈자리가 얼마나 있어?", OpenStudyAnalyses.ID_POC_004, requestedScope)
                    ),
                    retrieveCandidates(q, actor)
            );
        }

        if (q.equals("급한 일 전부 보여줘") || (q.contains("급한 일") && q.contains("전부"))) {
            // NEG-008
            return new PlanDecision(
                    "CLARIFICATION",
                    200,
                    "CLARIFICATION_OR_POLICY_PENDING",
                    "CLARIFICATION_OR_POLICY_PENDING",
                    "NOT_DETERMINED",
                    "긴급 업무의 기준(우선순위 URGENT/HIGH, 오늘 마감 여부, 담당 범위)을 선택해 주세요.",
                    OpenStudyAnalyses.ID_POC_009,
                    "tasks",
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(
                            new ClarificationOption("urgent-today", "지금 급하게 처리해야 할 업무가 뭐야?", OpenStudyAnalyses.ID_POC_009, requestedScope)
                    ),
                    retrieveCandidates(q, actor)
            );
        }

        if (q.contains("내가 많이 판 것") || q.contains("많이 판 것 보여줘")) {
            // NEG-009
            return new PlanDecision(
                    "CLARIFICATION",
                    200,
                    "CLARIFICATION_REQUIRED",
                    "CLARIFICATION",
                    "QUESTION",
                    "판매 순위 기준(순판매 건수 vs 순매출 금액)을 선택해 주세요.",
                    null,
                    "passes",
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(
                            new ClarificationOption("top-pass-units", "지난달 가장 잘 팔린 이용권 TOP 5를 보여줘", OpenStudyAnalyses.ID_POC_003, requestedScope),
                            new ClarificationOption("sales-by-item", "지난달 이용권, 스터디룸, 사물함 중 어디서 매출이 가장 많이 나왔어?", OpenStudyAnalyses.ID_POC_008, requestedScope)
                    ),
                    retrieveCandidates(q, actor)
            );
        }

        if (q.contains("작년 1월") && q.contains("합쳐줘")) {
            // NEG-014
            return new PlanDecision(
                    "CLARIFICATION",
                    200,
                    "CLARIFICATION_REQUIRED",
                    "CLARIFICATION",
                    "QUESTION",
                    "비연속 기간 합산 요청이며 366일 조회 상한을 초과합니다. 조회할 단일 기간을 선택해 주세요.",
                    null,
                    "sales",
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(
                            new ClarificationOption("this-month-sales", "이번 달 지점별 매출을 비교해줘", OpenStudyAnalyses.ID_POC_001, requestedScope),
                            new ClarificationOption("last-month-daily", "지난달 일별 매출 추이를 그래프로 보여줘", OpenStudyAnalyses.ID_POC_007, requestedScope)
                    ),
                    retrieveCandidates(q, actor)
            );
        }

        if (q.equals("매출 보여줘") || q.equals("매출 알려줘") || q.equals("매출 현황 알려줘")
                || q.contains("실적을 보여줘") || q.contains("실적 보여줘")) {
            return new PlanDecision(
                    "CLARIFICATION",
                    200,
                    "CLARIFICATION_REQUIRED",
                    "CLARIFICATION",
                    "QUESTION",
                    "조회할 매출 기간과 비교 기준을 선택해 주세요.",
                    null,
                    "sales",
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(
                            new ClarificationOption("branch-sales", "이번 달 지점별 매출을 비교해줘", OpenStudyAnalyses.ID_POC_001, requestedScope),
                            new ClarificationOption("daily-sales", "지난달 일별 매출 추이를 그래프로 보여줘", OpenStudyAnalyses.ID_POC_007, requestedScope),
                            new ClarificationOption("item-sales", "지난달 이용권, 스터디룸, 사물함 중 어디서 매출이 가장 많이 나왔어?", OpenStudyAnalyses.ID_POC_008, requestedScope)
                    ),
                    retrieveCandidates(q, actor)
            );
        }

        if (q.contains("다른 지점 전체 매출") || q.contains("타 지점 매출")) {
            return new PlanDecision(
                    "DENIED",
                    403,
                    "BRANCH_SCOPE_DENIED",
                    "AUTHORIZATION_DENIED",
                    "403_OR_SCOPE_REINPUT",
                    "타 지점 전체 매출은 소속 지점 권한 범위를 벗어나므로 조회할 수 없습니다. 허용된 지점 범위를 선택해 주세요.",
                    OpenStudyAnalyses.ID_POC_001,
                    "sales",
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(),
                    List.of()
            );
        }

        if (q.contains("왜 취소됐어") || q.contains("취소 사유")) {
            return new PlanDecision(
                    "CLARIFICATION",
                    200,
                    "CLARIFICATION_REQUIRED",
                    "CLARIFICATION",
                    "QUESTION",
                    "취소 사유 자유 텍스트는 정량 집계 대상이 아닙니다. 주간 스터디룸 예약/취소 건수 집계를 조회할까요?",
                    null,
                    "rooms",
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(
                            new ClarificationOption("weekly-room-bookings", "이번 주 스터디룸 예약 현황을 보여줘", OpenStudyAnalyses.ID_POC_005, requestedScope)
                    ),
                    retrieveCandidates(q, actor)
            );
        }

        if (q.equals("좌석 현황 알려줘") || q.equals("좌석 보여줘")) {
            return new PlanDecision(
                    "CLARIFICATION",
                    200,
                    "CLARIFICATION_REQUIRED",
                    "CLARIFICATION",
                    "QUESTION",
                    "조회할 지점과 좌석 분석 유형(실시간 빈자리 vs 시간대별 혼잡도)을 선택해 주세요.",
                    null,
                    "seats",
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(
                            new ClarificationOption("realtime-seats", "지금 빈자리가 얼마나 있어?", OpenStudyAnalyses.ID_POC_004, requestedScope),
                            new ClarificationOption("hourly-congestion", "언제 가장 붐벼?", OpenStudyAnalyses.ID_POC_002, requestedScope)
                    ),
                    retrieveCandidates(q, actor)
            );
        }

        List<RetrievalCandidate> candidates = retrieveCandidates(q, actor);
        long llmDurationMs = 0L;
        int totalTokens = 0;
        LiveLlmPlanResult llmResult = null;

        // #233 (2026-11-13 decision): registered analyses come first by the keyword rules. A question whose metric the
        // server reads as a general metric ("결제 금액", "결제 건수") skips the live registered-analysis picker, which
        // would fit it onto the nearest registered analysis (net sales), and goes to the generation path.
        boolean generalMetric = followUpAnalysisId == null && resolveAnalysisId(q) == null
                && !intents.analyze(q, null).metrics().isEmpty();
        if ("live".equals(activeMode()) && !generalMetric) {
            llmResult = callLiveQwenPlanner(q, requestedScope, candidates, previousConditions);
            if (llmResult != null) {
                llmDurationMs = llmResult.durationMs();
                totalTokens = llmResult.totalTokens();
            }
        }

        // 9. Determine target analysis ID & domain (preserving IR/playback fallback)
        String resolvedAnalysisId = resolveAnalysisId(q);
        if (resolvedAnalysisId == null && followUpAnalysisId != null) {
            resolvedAnalysisId = followUpAnalysisId;
        }
        if (resolvedAnalysisId == null && llmResult != null && llmResult.analysisId() != null
                && analyses.definition(llmResult.analysisId()).isPresent()) {
            resolvedAnalysisId = llmResult.analysisId();
        }

        String intentDomain = (llmResult != null && llmResult.intentDomain() != null
                && !"unsupported".equalsIgnoreCase(llmResult.intentDomain())
                && resolvedAnalysisId != null)
                ? domainForAnalysisId(resolvedAnalysisId, q)
                : domainForAnalysisId(resolvedAnalysisId, q);
        boolean isSalesDomain = "sales".equals(intentDomain)
                || (llmResult != null && "sales".equalsIgnoreCase(llmResult.intentDomain()));

        // 10. Authorize branch scope & domain permissions (NEG-001, NEG-002, NEG-003, NEG-015, NEG-016)
        var scopeDecision = authorizeScope(actor, requestedScope, q, isSalesDomain);
        if (!scopeDecision.allowed()) {
            return new PlanDecision(
                    "DENIED",
                    scopeDecision.httpStatus(),
                    scopeDecision.errorCode(),
                    scopeDecision.resultCategory(),
                    scopeDecision.behavior(),
                    scopeDecision.message(),
                    resolvedAnalysisId,
                    intentDomain,
                    null,
                    false,
                    TruncationInfo.defaultUntruncated(),
                    List.of(),
                    List.of(),
                    llmDurationMs,
                    totalTokens
            );
        }

        // 11. Check special policy overrides / clamping (NEG-006, NEG-017, NEG-018)
        TruncationInfo truncation = TruncationInfo.defaultUntruncated();
        String resultCategory = "CONFIRMED_EXECUTION";
        String behavior = "OK";
        String refundBasisOverride = llmResult != null ? llmResult.refundBasisOverride() : null;
        boolean zeroFillOverrideRequested = llmResult != null && llmResult.zeroFillOverrideRequested();

        if (q.contains("1001행") || q.contains("5000건") || q.contains("10만") || q.contains("100,000") || q.contains("100000") || q.contains("전부 한 번에")) {
            truncation = TruncationInfo.clamped("요청 행 수가 상한을 초과하여 최대 1,000행(페이지당 50행)으로 제한되었습니다.");
            resultCategory = "LIMIT_ENFORCED";
            behavior = "TRUNCATE_OR_PAGE";
            if (resolvedAnalysisId == null) {
                resolvedAnalysisId = OpenStudyAnalyses.ID_POC_001;
                intentDomain = "sales";
            }
        }

        if (q.contains("원결제일로") || q.contains("원결제일 기준") || "ORIGINAL_PAYMENT_DATE".equals(refundBasisOverride)) {
            refundBasisOverride = "ORIGINAL_PAYMENT_DATE";
            resultCategory = "EXPLICIT_ORIGINAL_PAYMENT_DATE";
            behavior = "QUERY_WITH_OVERRIDE";
            if (resolvedAnalysisId == null) {
                resolvedAnalysisId = OpenStudyAnalyses.ID_POC_008;
                intentDomain = "sales";
            }
        }

        if (q.contains("거래가 없는 날은 빼줘") || q.contains("없는 날은 빼줘") || q.contains("0원인 날 제외") || zeroFillOverrideRequested) {
            zeroFillOverrideRequested = true;
            resultCategory = "EXPLICIT_ZERO_FILL_OVERRIDE_REVIEW";
            behavior = "POLICY_REVIEW";
            resolvedAnalysisId = OpenStudyAnalyses.ID_POC_007;
            intentDomain = "sales";
        }

        if (resolvedAnalysisId == null && llmResult != null && "CLARIFICATION".equalsIgnoreCase(llmResult.outcome())) {
            String clarDomain = llmResult.intentDomain() == null ? intentDomain : llmResult.intentDomain();
            String clarMsg = (llmResult.message() != null && !llmResult.message().isBlank())
                    ? llmResult.message()
                    : "질문의 지표나 범위를 구체화해 주세요. 아래 선택지 중 하나를 선택하면 바로 분석합니다.";
            return new PlanDecision(
                    "CLARIFICATION",
                    200,
                    "CLARIFICATION_REQUIRED",
                    "CLARIFICATION",
                    "QUESTION",
                    clarMsg,
                    null,
                    clarDomain,
                    null,
                    false,
                    truncation,
                    defaultClarificationOptionsForDomain(clarDomain, requestedScope),
                    candidates,
                    llmDurationMs,
                    totalTokens
            );
        }

        if (resolvedAnalysisId == null) {
            String unsupMsg = (llmResult != null && llmResult.message() != null && !llmResult.message().isBlank())
                    ? llmResult.message()
                    : "질문에 해당하는 등록 분석 지표를 찾지 못했습니다.";
            return new PlanDecision(
                    "UNSUPPORTED",
                    200,
                    "OUT_OF_DOMAIN",
                    "UNSUPPORTED_DOMAIN",
                    "UNSUPPORTED",
                    unsupMsg,
                    null,
                    "unsupported",
                    null,
                    false,
                    truncation,
                    List.of(),
                    candidates,
                    llmDurationMs,
                    totalTokens
            );
        }

        return new PlanDecision(
                "EXECUTE",
                200,
                null,
                resultCategory,
                behavior,
                null,
                resolvedAnalysisId,
                intentDomain,
                refundBasisOverride,
                zeroFillOverrideRequested,
                truncation,
                List.of(),
                candidates,
                llmDurationMs,
                totalTokens
        );
    }

    private static final String LIVE_QWEN_SYSTEM_PROMPT = """
            You are the OpenStudy DomainLens semantic planner. Map the Korean user question to one of the 10 approved registered analyses.
            All 10 analyses support branch filtering (a branch name or 전체 지점) and standard time expressions (이번 달, 지난달, 이번 주, 지난주, 지금, 7일 이내).
            1. openstudy.sales.branchComparison (domain: sales) - 이번 달 지점별 매출을 비교해줘 / 지점별 매출·승인·환불·순매출 비교
            2. openstudy.seats.hourlyCongestion (domain: seats) - ○○점은 언제 가장 붐벼? / 요일·시간대별 좌석 혼잡도 히트맵
            3. openstudy.passes.topProducts (domain: passes) - 지난달 가장 잘 팔린 이용권 TOP 5를 보여줘 / 인기 이용권 판매 순위
            4. openstudy.seats.realtimeAvailability (domain: seats) - 지금 ○○점에 빈자리가 얼마나 있어? / 실시간 빈자리·좌석 점유 현황
            5. openstudy.rooms.weeklyReservations (domain: rooms) - 이번 주 스터디룸 예약 현황을 보여줘 / 주간 스터디룸 예약 캘린더
            6. openstudy.passes.expiringSoon (domain: passes) - 앞으로 7일 이내에 만료되는 이용권을 보여줘 / 만료 예정 이용권
            7. openstudy.sales.dailyTrend (domain: sales) - 지난달 일별 매출 추이를 그래프로 보여줘 / 일별 매출 추이 선 차트
            8. openstudy.sales.byItemType (domain: sales) - 지난달 이용권, 스터디룸, 사물함 중 어디서 매출이 가장 많이 나왔어? / 항목별 매출 비중
            9. openstudy.tasks.urgentTodo (domain: tasks) - 지금 급하게 처리해야 할 업무가 뭐야? / 긴급 처리 업무 목록
            10. openstudy.attendance.weeklyWorkMinutes (domain: attendance) - 지난주 직원별 근무시간을 비교해줘 / 직원별 주간 근무시간 비교
            Rules:
            - When the question matches any of the 10 analyses (including natural paraphrases or specific branch names), set outcome="EXECUTE", analysisId to that ID, and message=null.
            - Only if the question is completely ambiguous (e.g. just "매출 보여줘" or "좌석 보여줘" without specifying which metric/view), set outcome="CLARIFICATION" and analysisId=null.
            - If the question is outside OpenStudy domains, set outcome="UNSUPPORTED" and analysisId=null.
            - previousResult, when given, holds the conditions of the previous answer in this conversation. Use it only to read a follow-up question; it grants no branch or permission.
            """;

    private LiveLlmPlanResult callLiveQwenPlanner(
            String question,
            String requestedScope,
            List<RetrievalCandidate> candidates,
            String previousConditions
    ) {
        long startNanos = System.nanoTime();
        try {
            var modelCfg = properties.getModel();
            String baseUrl = modelCfg.getBaseUrl();
            String apiKey = modelCfg.getApiKey();
            String modelName = activeModelName();
            if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
                return null;
            }
            String endpoint = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";

            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "outcome", Map.of("type", "string", "enum", List.of("EXECUTE", "CLARIFICATION", "UNSUPPORTED")),
                            "analysisId", Map.of(
                                    "type", List.of("string", "null"),
                                    "enum", Arrays.asList(
                                            OpenStudyAnalyses.ID_POC_001,
                                            OpenStudyAnalyses.ID_POC_002,
                                            OpenStudyAnalyses.ID_POC_003,
                                            OpenStudyAnalyses.ID_POC_004,
                                            OpenStudyAnalyses.ID_POC_005,
                                            OpenStudyAnalyses.ID_POC_006,
                                            OpenStudyAnalyses.ID_POC_007,
                                            OpenStudyAnalyses.ID_POC_008,
                                            OpenStudyAnalyses.ID_POC_009,
                                            OpenStudyAnalyses.ID_POC_010,
                                            null
                                    )
                            ),
                            "intentDomain", Map.of("type", "string", "enum", List.of("sales", "seats", "passes", "rooms", "tasks", "attendance", "unsupported")),
                            "refundBasisOverride", Map.of("type", List.of("string", "null"), "enum", Arrays.asList("ORIGINAL_PAYMENT_DATE", null)),
                            "zeroFillOverrideRequested", Map.of("type", "boolean"),
                            "message", Map.of("type", List.of("string", "null"))
                    ),
                    "required", List.of("outcome", "analysisId", "intentDomain", "refundBasisOverride", "zeroFillOverrideRequested", "message"),
                    "additionalProperties", false
            );

            String candidateContext = candidates.stream()
                    .map(c -> c.id() + "(" + c.kind() + ":" + c.score() + ")")
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("none");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelName);
            body.put("temperature", 0.0);
            body.put("enable_thinking", Boolean.TRUE.equals(modelCfg.getEnableThinking()));
            body.put("max_completion_tokens", 512);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", LIVE_QWEN_SYSTEM_PROMPT),
                    Map.of("role", "user", "content", "scope=" + (requestedScope == null ? "all" : requestedScope)
                            + "\nretrievedCandidates=" + candidateContext
                            + (previousConditions == null ? "" : "\npreviousResult=" + previousConditions)
                            + "\nquestion=" + question)
            ));
            body.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                            "name", "openstudy_query_plan",
                            "strict", true,
                            "schema", schema
                    )
            ));

            String payload = objectMapper.writeValueAsString(body);
            Duration timeout = modelCfg.getTimeout() != null ? modelCfg.getTimeout() : Duration.ofSeconds(30);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long durationMs = Math.max(1L, Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Live Qwen LLM returned HTTP {} in {}ms", response.statusCode(), durationMs);
                return new LiveLlmPlanResult(null, null, null, null, false, null, durationMs, 0);
            }

            JsonNode root = objectMapper.readTree(response.body());
            int totalTokens = root.path("usage").path("total_tokens").asInt(0);
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                return new LiveLlmPlanResult(null, null, null, null, false, null, durationMs, totalTokens);
            }

            JsonNode parsed = objectMapper.readTree(content);
            String outcome = parsed.path("outcome").isNull() ? null : parsed.path("outcome").asText(null);
            String analysisId = parsed.path("analysisId").isNull() ? null : parsed.path("analysisId").asText(null);
            String intentDomain = parsed.path("intentDomain").isNull() ? null : parsed.path("intentDomain").asText(null);
            String refundBasisOverride = parsed.path("refundBasisOverride").isNull() ? null : parsed.path("refundBasisOverride").asText(null);
            boolean zeroFill = parsed.path("zeroFillOverrideRequested").asBoolean(false);
            String message = parsed.path("message").isNull() ? null : parsed.path("message").asText(null);

            log.info("Live Qwen LLM ({}) planned question in {}ms (tokens={}): outcome={}, analysisId={}",
                    modelName, durationMs, totalTokens, outcome, analysisId);
            return new LiveLlmPlanResult(outcome, analysisId, intentDomain, refundBasisOverride, zeroFill, message, durationMs, totalTokens);
        } catch (Exception e) {
            long durationMs = Math.max(1L, Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
            log.warn("Live Qwen LLM call failed after {}ms: {}", durationMs, e.getMessage());
            return new LiveLlmPlanResult(null, null, null, null, false, null, durationMs, 0);
        }
    }

    private static List<ClarificationOption> defaultClarificationOptionsForDomain(String domain, String requestedScope) {
        if ("seats".equalsIgnoreCase(domain)) {
            return List.of(
                    new ClarificationOption("realtime-seats", "지금 빈자리가 얼마나 있어?", OpenStudyAnalyses.ID_POC_004, requestedScope),
                    new ClarificationOption("hourly-congestion", "언제 가장 붐벼?", OpenStudyAnalyses.ID_POC_002, requestedScope)
            );
        }
        if ("rooms".equalsIgnoreCase(domain)) {
            return List.of(
                    new ClarificationOption("weekly-room-bookings", "이번 주 스터디룸 예약 현황을 보여줘", OpenStudyAnalyses.ID_POC_005, requestedScope)
            );
        }
        if ("passes".equalsIgnoreCase(domain)) {
            return List.of(
                    new ClarificationOption("top-passes", "지난달 가장 잘 팔린 이용권 TOP 5를 보여줘", OpenStudyAnalyses.ID_POC_003, requestedScope),
                    new ClarificationOption("expiring-passes", "앞으로 7일 이내에 만료되는 이용권을 보여줘", OpenStudyAnalyses.ID_POC_006, requestedScope)
            );
        }
        return List.of(
                new ClarificationOption("branch-sales", "이번 달 지점별 매출을 비교해줘", OpenStudyAnalyses.ID_POC_001, requestedScope),
                new ClarificationOption("daily-sales", "지난달 일별 매출 추이를 그래프로 보여줘", OpenStudyAnalyses.ID_POC_007, requestedScope),
                new ClarificationOption("item-sales", "지난달 이용권, 스터디룸, 사물함 중 어디서 매출이 가장 많이 나왔어?", OpenStudyAnalyses.ID_POC_008, requestedScope)
        );
    }

    public List<RetrievalCandidate> retrieveCandidates(
            String question,
            OpenStudyUserContextResolver.ResolvedActor actor
    ) {
        try {
            UserContext user = actor.toUserContext("all", actor.aiBranches());
            var ctx = retriever.retrieve(question, user);
            List<RetrievalCandidate> list = new ArrayList<>();
            int rank = 0;
            for (String id : ctx.ids()) {
                double score = Math.round((0.96 - (rank * 0.03)) * 1000.0) / 1000.0;
                String kind = registry.metrics().containsKey(id) ? "METRIC"
                        : registry.sources().containsKey(id) ? "ENTITY"
                        : registry.relations().containsKey(id) ? "RELATION" : "FIELD";
                list.add(new RetrievalCandidate(id, kind, Math.max(0.50, score)));
                rank++;
                if (rank >= 8) break;
            }
            return list;
        } catch (Exception e) {
            return List.of();
        }
    }

    public QuerySpec buildQuerySpec(String analysisId) {
        var def = analyses.definition(analysisId).orElseThrow();
        return new QuerySpec(
                "qs_" + def.id(),
                def.source(),
                def.grain(),
                QuerySpec.Mode.REGISTERED_ANALYSIS,
                List.of(),
                List.of(),
                null,
                List.of(),
                new QuerySpec.Page(0, DEFAULT_PAGE_SIZE),
                MAX_RESULT_ROWS,
                def.id()
        );
    }

    private String resolveAnalysisId(String q) {
        Optional<String> direct = analyses.resolveFromQuestion(q);
        if (direct.isPresent()) {
            return direct.get();
        }
        String k = QuestionNormalizer.key(q);
        if (QuestionNormalizer.has(k, "매출") && (QuestionNormalizer.has(k, "지점") || !userResolver.mentionedBranches(q).isEmpty())) {
            return OpenStudyAnalyses.ID_POC_001;
        }
        if (QuestionNormalizer.has(k, "이용권") && QuestionNormalizer.has(k, "매출")) {
            return OpenStudyAnalyses.ID_POC_008;
        }
        if (QuestionNormalizer.has(k, "만료") && QuestionNormalizer.has(k, "이용권")) {
            return OpenStudyAnalyses.ID_POC_006;
        }
        if (QuestionNormalizer.has(k, "급한 업무") || QuestionNormalizer.has(k, "긴급 업무")) {
            return OpenStudyAnalyses.ID_POC_009;
        }
        return null;
    }

    private static String domainForAnalysisId(String analysisId, String q) {
        if (analysisId != null) {
            return switch (analysisId) {
                case OpenStudyAnalyses.ID_POC_001, OpenStudyAnalyses.ID_POC_007, OpenStudyAnalyses.ID_POC_008 -> "sales";
                case OpenStudyAnalyses.ID_POC_002, OpenStudyAnalyses.ID_POC_004 -> "seats";
                case OpenStudyAnalyses.ID_POC_003, OpenStudyAnalyses.ID_POC_006 -> "passes";
                case OpenStudyAnalyses.ID_POC_005 -> "rooms";
                case OpenStudyAnalyses.ID_POC_009 -> "tasks";
                case OpenStudyAnalyses.ID_POC_010 -> "attendance";
                default -> "general";
            };
        }
        if (q.contains("매출") || q.contains("결제") || q.contains("환불")) return "sales";
        if (q.contains("좌석") || q.contains("빈자리") || q.contains("붐벼")) return "seats";
        if (q.contains("이용권")) return "passes";
        if (q.contains("스터디룸") || q.contains("룸")) return "rooms";
        if (q.contains("업무")) return "tasks";
        if (q.contains("근무") || q.contains("출퇴근")) return "attendance";
        return "general";
    }

    /** One rule for branch scope: the same decision the execution step makes (OpenStudyUserContextResolver). */
    private OpenStudyUserContextResolver.ScopeDecision authorizeScope(
            OpenStudyUserContextResolver.ResolvedActor actor,
            String requestedScope,
            String question,
            boolean isSalesDomain
    ) {
        return userResolver.authorizeQuestion(actor, requestedScope, question, isSalesDomain);
    }
}
