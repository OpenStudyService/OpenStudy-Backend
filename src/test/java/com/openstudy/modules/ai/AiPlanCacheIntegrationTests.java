package com.openstudy.modules.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.openstudy.modules.ai.service.AiPlanCache;
import com.openstudy.modules.ai.service.AiPlanCache.Kind;
import com.openstudy.modules.ai.service.OpenStudyAnalyses;
import com.openstudy.support.database.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 2026-11-16: which questions share a cached plan. Embeddings alone would join "가장 많이" and "가장 적게" (0.94 with
 * koen-e5-tiny-q8), so a plan is reused only when the server also reads both questions the same way.
 */
@MySqlIntegrationTest
@EnabledIf("com.openstudy.domainlens.LocalAiFixtures#modelInstalled")
class AiPlanCacheIntegrationTests extends AiTestSupport {
    static final String SOLD_MOST = "이번 달 가장 많이 판매된 이용권을 보여줘";

    @Autowired AiPlanCache cache;

    @Test void theServerReadsQuestionsThatOnlyLookAlikeDifferently() {
        // The same meaning, other words: one reading.
        assertThat(cache.reading("이번 달 제일 많이 팔린 이용권 보여줘")).isEqualTo(cache.reading(SOLD_MOST));
        assertThat(cache.reading("이번달 지점별 결제 금액 합계를 보여줘"))
                .isEqualTo(cache.reading("이번 달 지점별 결제 금액 합계 보여줘"));
        // Close embeddings, another question.
        assertThat(cache.reading("이번 달 가장 적게 판매된 이용권을 보여줘")).isNotEqualTo(cache.reading(SOLD_MOST));
        assertThat(cache.reading("지난달 가장 많이 판매된 이용권을 보여줘")).isNotEqualTo(cache.reading(SOLD_MOST));
        assertThat(cache.reading("이번 달 결제 건수 합계 보여줘")).isNotEqualTo(cache.reading("이번 달 결제 금액 합계 보여줘"));
        assertThat(cache.reading("이번 달 환불 금액 합계 보여줘")).isNotEqualTo(cache.reading("이번 달 결제 금액 합계 보여줘"));
        assertThat(cache.reading("상품별 결제 금액 보여줘")).isNotEqualTo(cache.reading("지점별 결제 금액 보여줘"));
        assertThat(cache.reading("시간대별 좌석 점유율 보여줘")).isNotEqualTo(cache.reading("요일별 좌석 점유율 보여줘"));
        assertThat(cache.reading("최근 30일 결제 금액")).isNotEqualTo(cache.reading("최근 7일 결제 금액"));
    }

    @Test void aPickedAnalysisIsFoundForTheSameMeaningOnlyAndForItsVersionOnly() {
        String version = cache.versionKey(Kind.REGISTERED_PICK, "test-model", "prompt-1");
        cache.storePick(version, SOLD_MOST, OpenStudyAnalyses.ID_POC_003, "passes", null, false);

        var same = cache.find(Kind.REGISTERED_PICK, version, "이번 달 제일 많이 팔린 이용권 보여줘");
        assertThat(same).isPresent();
        assertThat(same.get().analysisId()).isEqualTo(OpenStudyAnalyses.ID_POC_003);
        assertThat(same.get().similarity()).isGreaterThanOrEqualTo(0.90);
        assertThat(cache.find(Kind.REGISTERED_PICK, version, SOLD_MOST)).isPresent();

        assertThat(cache.find(Kind.REGISTERED_PICK, version, "이번 달 가장 적게 판매된 이용권을 보여줘")).isEmpty();
        assertThat(cache.find(Kind.REGISTERED_PICK, version, "지난달 가장 많이 판매된 이용권을 보여줘")).isEmpty();
        // Another model or prompt never reads these plans; nor does the other kind.
        assertThat(cache.find(Kind.REGISTERED_PICK, cache.versionKey(Kind.REGISTERED_PICK, "test-model", "prompt-2"),
                SOLD_MOST)).isEmpty();
        assertThat(cache.find(Kind.GENERATED, version, SOLD_MOST)).isEmpty();
        // Every hit counts; the plan is shared, nothing in it names who asked.
        assertThat(count("SELECT hit_count FROM ai_plan_cache WHERE kind='REGISTERED_PICK'")).isEqualTo(2L);
    }

    @Test void anExpiredPlanIsNotUsed() {
        String version = cache.versionKey(Kind.REGISTERED_PICK, "test-model", "prompt-1");
        cache.storePick(version, SOLD_MOST, OpenStudyAnalyses.ID_POC_003, "passes", null, false);
        jdbc.update("UPDATE ai_plan_cache SET expires_at = created_at");
        assertThat(cache.find(Kind.REGISTERED_PICK, version, SOLD_MOST)).isEmpty();
    }
}
