package com.openstudy.modules.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.openstudy.config.DomainLensProperties;
import com.openstudy.config.OpenStudyCatalogConfiguration;
import com.openstudy.domainlens.ai.LiveModelGatewayFactory;
import com.openstudy.domainlens.ai.OnnxEmbeddingGateway;
import com.openstudy.domainlens.api.prompt.PromptLimits;
import com.openstudy.domainlens.catalog.EmbeddingIndex;
import com.openstudy.domainlens.catalog.SearchDocuments;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import com.openstudy.domainlens.query.Retriever;
import com.openstudy.modules.ai.service.OpenStudyAnalyses;
import com.openstudy.modules.ai.service.OpenStudyPlaybackGateway;
import com.openstudy.modules.ai.service.OpenStudyUserContextResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 7 (`OS-DL-0702`) ONNX embedding (`koen-e5-tiny-q8`) semantic retrieval Recall@K evaluation
 * and Qwen LLM (`qwen3.8-flash`) gateway verification test suite.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/openstudy?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "spring.datasource.username=openstudy_app",
                "spring.datasource.password=${OPENSTUDY_AI_TEST_DB_PASSWORD:}",
                "spring.flyway.enabled=true",
                "spring.flyway.url=jdbc:mysql://127.0.0.1:3307/openstudy?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "spring.flyway.user=openstudy_app",
                "spring.flyway.password=${OPENSTUDY_AI_TEST_DB_PASSWORD:}",
                "openstudy.ai.fixed-as-of=2026-11-02T05:00:00Z"
        }
)
@ActiveProfiles("test")
@EnabledIf("com.openstudy.domainlens.LocalAiFixtures#localAiTestsEnabled")
class OpenStudyRetrievalEvaluationTests {

    @Autowired
    SemanticRegistry registry;

    @Autowired
    EmbeddingIndex embeddingIndex;

    @Autowired
    Retriever retriever;

    @Autowired
    OpenStudyPlaybackGateway playbackGateway;

    @Autowired
    OpenStudyUserContextResolver userResolver;

    @Autowired
    DomainLensProperties domainLensProperties;

    record QuestionSpec(String testId, String question, String expectedDomain, String expectedAnalysisId, String expectedMetricId) {}

    private static final Map<String, String> POC_TO_ANALYSIS_ID = Map.of(
            "POC-001", OpenStudyAnalyses.ID_POC_001,
            "POC-002", OpenStudyAnalyses.ID_POC_002,
            "POC-003", OpenStudyAnalyses.ID_POC_003,
            "POC-004", OpenStudyAnalyses.ID_POC_004,
            "POC-005", OpenStudyAnalyses.ID_POC_005,
            "POC-006", OpenStudyAnalyses.ID_POC_006,
            "POC-007", OpenStudyAnalyses.ID_POC_007,
            "POC-008", OpenStudyAnalyses.ID_POC_008,
            "POC-009", OpenStudyAnalyses.ID_POC_009,
            "POC-010", OpenStudyAnalyses.ID_POC_010
    );

    private static final Map<String, String> POC_TO_METRIC_ID = Map.of(
            "POC-001", "sales.net_sales_krw",
            "POC-002", "seats.hourly_congestion",
            "POC-003", "passes.top_products",
            "POC-004", "seats.realtime_availability",
            "POC-005", "rooms.weekly_reservations",
            "POC-006", "passes.expiring_soon",
            "POC-007", "sales.daily_trend",
            "POC-008", "sales.by_item_type",
            "POC-009", "tasks.urgent_todo",
            "POC-010", "attendance.weekly_work_minutes"
    );

    @Test
    void onnxKoenE5TinyQ8ModelProduces384DimensionalL2NormalizedVectors() {
        Path modelDir = OpenStudyCatalogConfiguration.resolvePath(null, ".models/koen-e5-tiny-q8");
        try (var gateway = new OnnxEmbeddingGateway(modelDir)) {
            assertThat(gateway.dimensions()).isEqualTo(384);
            List<Double> vec = gateway.embedQuery("이번 달 지점별 매출을 비교해줘");
            assertThat(vec).hasSize(384);

            double normSq = 0.0;
            for (double v : vec) {
                normSq += v * v;
            }
            double l2Norm = Math.sqrt(normSq);
            assertThat(l2Norm).isBetween(0.99, 1.01);
        }
    }

    @Test
    void questionsCsvAndKoreanParaphrasesAchieve100PercentRecallForDomainAndAnalysisId() throws Exception {
        Path csvPath = resolveDocPath("docs/05_TEST_CASES/questions.csv");
        List<String> lines = Files.readAllLines(csvPath);
        assertThat(lines.size()).isGreaterThanOrEqualTo(11);

        List<QuestionSpec> specs = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String testId = line.substring(0, 7);
            String domain = switch (testId) {
                case "POC-001", "POC-007", "POC-008" -> "sales";
                case "POC-002", "POC-004" -> "seats";
                case "POC-003", "POC-006" -> "passes";
                case "POC-005" -> "rooms";
                case "POC-009" -> "tasks";
                case "POC-010" -> "attendance";
                default -> throw new IllegalStateException("Unexpected testId: " + testId);
            };
            // Extract question from CSV
            String afterId = line.substring(8);
            String question;
            if (afterId.startsWith("\"")) {
                int endQuote = afterId.indexOf("\"", 1);
                question = afterId.substring(1, endQuote);
            } else {
                question = afterId.substring(0, afterId.indexOf(","));
            }
            specs.add(new QuestionSpec(
                    testId,
                    question,
                    domain,
                    POC_TO_ANALYSIS_ID.get(testId),
                    POC_TO_METRIC_ID.get(testId)
            ));
        }

        // Also add Korean paraphrase variants
        specs.addAll(List.of(
                new QuestionSpec("POC-001-P", "이번달 강남점 성수점 지점별 매출 비교 알려줘", "sales", OpenStudyAnalyses.ID_POC_001, "sales.net_sales_krw"),
                new QuestionSpec("POC-002-P", "강남점 시간대별 좌석 혼잡도 피크 시간 알려줘", "seats", OpenStudyAnalyses.ID_POC_002, "seats.hourly_congestion"),
                new QuestionSpec("POC-003-P", "지난달 인기 이용권 TOP 5 순위 보여줘", "passes", OpenStudyAnalyses.ID_POC_003, "passes.top_products"),
                new QuestionSpec("POC-004-P", "지금 강남점 남은 좌석 빈자리 몇 개야?", "seats", OpenStudyAnalyses.ID_POC_004, "seats.realtime_availability"),
                new QuestionSpec("POC-005-P", "이번 주 스터디룸 예약 스케줄 보여줘", "rooms", OpenStudyAnalyses.ID_POC_005, "rooms.weekly_reservations"),
                new QuestionSpec("POC-006-P", "7일 이내 만료 예정인 이용권 목록 보여줘", "passes", OpenStudyAnalyses.ID_POC_006, "passes.expiring_soon"),
                new QuestionSpec("POC-007-P", "지난달 일별 매출 추이 선 차트로 그려줘", "sales", OpenStudyAnalyses.ID_POC_007, "sales.daily_trend"),
                new QuestionSpec("POC-008-P", "이용권 스터디룸 사물함 중 어디서 매출이 제일 많이 나왔어?", "sales", OpenStudyAnalyses.ID_POC_008, "sales.by_item_type"),
                new QuestionSpec("POC-009-P", "지금 급하게 처리해야 할 긴급 업무 보여줘", "tasks", OpenStudyAnalyses.ID_POC_009, "tasks.urgent_todo"),
                new QuestionSpec("POC-010-P", "지난주 직원별 근무시간 비교해줘", "attendance", OpenStudyAnalyses.ID_POC_010, "attendance.weekly_work_minutes")
        ));

        var owner = userResolver.resolveByActorIdentifier("ACTIVE_OWNER");
        int hits = 0;

        for (QuestionSpec spec : specs) {
            var plan = playbackGateway.planQuestion(spec.question(), null, owner, "all");
            assertThat(plan.outcome())
                    .as("%s (%s) outcome", spec.testId(), spec.question())
                    .isEqualTo("EXECUTE");
            assertThat(plan.analysisId())
                    .as("%s (%s) analysisId", spec.testId(), spec.question())
                    .isEqualTo(spec.expectedAnalysisId());
            assertThat(plan.intentDomain())
                    .as("%s (%s) intentDomain", spec.testId(), spec.question())
                    .isEqualTo(spec.expectedDomain());

            var retrieved = retriever.retrieve(spec.question(), owner.toUserContext("all", owner.aiBranches()));
            assertThat(retrieved.ids())
                    .as("%s (%s) ONNX Retriever context", spec.testId(), spec.question())
                    .contains(spec.expectedMetricId());
            hits++;
        }

        double recallPct = (hits * 100.0) / specs.size();
        assertThat(recallPct).isEqualTo(100.0);
    }

    @Test
    void searchDocumentsNeverContainCustomerEntityOrSensitiveFields() {
        var docs = new SearchDocuments().build(registry);
        assertThat(docs).isNotEmpty();

        Set<String> forbiddenIds = Set.of(
                "customer",
                "customer.id",
                "customer.member_no",
                "customer.name",
                "customer.phone",
                "customer.email",
                "customer.memo",
                "employee.email",
                "employee.password_hash",
                "branch.phone",
                "work_task.body",
                "refund.reason"
        );

        for (var doc : docs) {
            assertThat(forbiddenIds)
                    .as("SearchDocument id %s must not be a forbidden PII/credential node", doc.id())
                    .doesNotContain(doc.id());
            assertThat(doc.text().toLowerCase())
                    .doesNotContain("password_hash", "token_hash", "customer.phone");
        }
    }

    @Test
    void qwenLiveModelGatewayConfigurationLoadsFromEnvAndConstructsGateway() {
        var modelCfg = domainLensProperties.getModel();
        assertThat(modelCfg.getBaseUrl())
                .isEqualTo("https://token-plan.maas.qwencloudapi.com/compatible-mode/v1");
        assertThat(modelCfg.getName())
                .isEqualTo("qwen3.8-flash");
        assertThat(modelCfg.getEnableThinking())
                .isFalse();

        String apiKey = modelCfg.getApiKey() != null && !modelCfg.getApiKey().isBlank()
                ? modelCfg.getApiKey()
                : "sk-test-placeholder-key";

        var gateway = LiveModelGatewayFactory.create(
                modelCfg.getBaseUrl(),
                apiKey,
                modelCfg.getName(),
                modelCfg.getTimeout(),
                PromptLimits.defaults(),
                modelCfg.getEnableThinking(),
                modelCfg.getReasoningEffort(),
                modelCfg.getMaxCompletionTokens()
        );
        assertThat(gateway).isNotNull();
    }

    private static Path resolveDocPath(String relative) {
        Path p1 = Path.of("..", relative);
        if (Files.exists(p1)) return p1;
        Path p2 = Path.of(relative);
        if (Files.exists(p2)) return p2;
        return Path.of("/Users/seeker/Projects/JavaProjects/OpenStudy", relative);
    }
}
