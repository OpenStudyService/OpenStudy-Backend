package com.openstudy.modules.ai.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.openstudy.domainlens.catalog.SemanticRegistry;

import static com.openstudy.modules.ai.service.OpenStudyAnalyses.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * #126 (M15-D03·D08): every reviewed synonym and typo, and the representative variant set: each variant picks the same
 * registered analysis as the original question, so it gets the same query spec and the same result.
 */
class QuestionNormalizerUnitTests {
    final OpenStudyAnalyses analyses = new OpenStudyAnalyses(null, null, OpenStudyClock.fixed(OpenStudyClock.DEFAULT_AS_OF),
            mock(SemanticRegistry.class), null);

    /** One case per entry in QuestionNormalizer.REPLACEMENTS; a new entry without a case fails here. */
    static final Map<String, String> REVIEWED = Map.ofEntries(
            Map.entry("매상", "매출"), Map.entry("락커", "사물함"), Map.entry("라커", "사물함"), Map.entry("빈좌석", "빈자리"),
            Map.entry("근로시간", "근무시간"), Map.entry("저번달", "지난달"), Map.entry("전월", "지난달"),
            Map.entry("저번주", "지난주"), Map.entry("탑5", "TOP5"), Map.entry("상위5", "TOP5"),
            Map.entry("매츨", "매출"), Map.entry("스터디름", "스터디룸"), Map.entry("스터디롬", "스터디룸"));

    @Test void everyReviewedReplacementHasItsCase() {
        assertThat(QuestionNormalizer.REPLACEMENTS).isEqualTo(REVIEWED);
        REVIEWED.forEach((word, canonical) -> assertThat(QuestionNormalizer.key("지난 " + word + " 보여줘"))
                .as(word).isEqualTo(QuestionNormalizer.key("지난 " + canonical + " 보여줘")));
    }

    @Test void spacingAndAsciiCaseDoNotMatter() {
        assertThat(QuestionNormalizer.key(" 지난달  top 5\t이용권 ")).isEqualTo("지난달TOP5이용권");
        assertThat(QuestionNormalizer.has(QuestionNormalizer.key("지점별매출"), "지점별 매출")).isTrue();
        assertThat(QuestionNormalizer.key(null)).isEmpty();
    }

    /** original question → its variants (synonyms, typos, spacing). */
    static final Map<String, List<String>> VARIANTS = Map.of(
            "이번 달 지점별 매출을 비교해줘", List.of("이번달 지점별매출 비교해줘", "이번 달 지점별 매상 비교", "이번달 지점별 매츨 비교해줘"),
            "강남점은 언제 가장 붐벼?", List.of("강남점 언제가장 붐벼", "강남점 혼잡 시간대"),
            "지난달 가장 잘 팔린 이용권 TOP 5를 보여줘", List.of("저번달 잘팔린 이용권 탑5", "지난달 이용권 top 5", "전월 이용권 상위 5개"),
            "지금 강남점에 빈자리가 얼마나 있어?", List.of("지금 빈 자리 몇 개야", "빈좌석 알려줘"),
            "이번 주 스터디룸 예약 현황을 보여줘", List.of("이번주 스터디 룸 예약", "이번 주 스터디름 예약 현황", "스터디롬예약 보여줘"),
            "앞으로 7일 이내에 만료되는 이용권을 보여줘", List.of("7일이내 만료되는 이용권", "만료예정 이용권"),
            "지난달 일별 매출 추이를 그래프로 보여줘", List.of("저번달 일별매출 추이", "지난달 매상 추이"),
            "지난달 이용권, 스터디룸, 사물함 중 어디서 매출이 가장 많이 나왔어?",
            List.of("지난달 이용권 스터디룸 락커 중 어디가 제일 많아", "어디서 매상이 가장 많이 나왔어"),
            "지금 급하게 처리해야 할 업무가 뭐야?", List.of("급하게처리해야할 업무 알려줘", "긴급업무"),
            "지난주 직원별 근무시간을 비교해줘", List.of("저번주 직원별 근로시간 비교", "직원별 근무 시간"));

    @Test void theRepresentativeVariantsPickTheSameAnalysis() {
        VARIANTS.forEach((original, variants) -> {
            Optional<String> expected = analyses.resolveFromQuestion(original);
            assertThat(expected).as(original).isPresent();
            for (String variant : variants) assertThat(analyses.resolveFromQuestion(variant)).as(variant).isEqualTo(expected);
        });
        assertThat(VARIANTS.values().stream().map(v -> analyses.resolveFromQuestion(v.getFirst()).orElseThrow()).distinct())
                .containsExactlyInAnyOrderElementsOf(OpenStudyAnalyses.REGISTERED);
    }

    @Test void anUnregisteredWordIsNotGuessed() {
        for (String question : List.of("날씨 알려줘", "고객 만족도 보여줘", "매출 보여줘", "전주점 매출", "이용건수 보여줘", "직원 평가 점수"))
            assertThat(analyses.resolveFromQuestion(question)).as(question).isEmpty();
    }

    @Test void aFollowUpNamesABranchOrAConditionPhrase() {
        assertThat(AiReuse.isFollowUp("강남점만", true)).isTrue();
        for (String question : List.of("같은 조건으로 다시", "그대로 보여줘", "원결제일 기준으로", "거래가 없는 날은 빼줘", "0원인 날 제외"))
            assertThat(AiReuse.isFollowUp(question, false)).as(question).isTrue();
        for (String question : List.of("날씨 알려줘", "고마워", "다른 거"))
            assertThat(AiReuse.isFollowUp(question, false)).as(question).isFalse();
        assertThat(AiReuse.isFollowUp(null, false)).isFalse();
    }

    @Test void theContextTellsInheritedFromChangedAndWhyItWasCut() {
        assertThat(AiReuse.resetContext("NO_PRIOR_RESULT")).containsEntry("reset", false).containsEntry("resetReason", "NO_PRIOR_RESULT")
                .containsEntry("basedOnExecutionId", null).containsEntry("inherited", List.of());
        assertThat(AiReuse.resetContext("SCOPE_CHANGED")).containsEntry("reset", true);
        assertThat(AiReuse.resetContext("PERMISSION_LOST")).containsEntry("reset", true);

        // Same sales analysis, the branch changed and the refund basis kept.
        var sameSales = AiReuse.followUpContext("exec-1", true, true, true, false, false, null);
        assertThat(sameSales).containsEntry("basedOnExecutionId", "exec-1").containsEntry("reset", false)
                .containsEntry("inherited", List.of("ANALYSIS", "PERIOD", "REFUND_BASIS")).containsEntry("changed", List.of("BRANCHES"));
        // Daily trend: zero-fill asked for this time.
        assertThat(AiReuse.followUpContext("exec-1", true, true, false, false, false, true).get("changed")).isEqualTo(List.of("ZERO_FILL"));
        // Another analysis keeps only the branches.
        var other = AiReuse.followUpContext("exec-1", false, false, false, false, null, null);
        assertThat(other).containsEntry("inherited", List.of("BRANCHES")).containsEntry("changed", List.of("ANALYSIS"));
    }
}
