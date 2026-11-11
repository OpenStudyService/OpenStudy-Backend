package com.openstudy.modules.ai.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.openstudy.common.exception.ApiException;
import com.openstudy.domainlens.api.ir.ResultEnvelope;
import com.openstudy.domainlens.api.ir.TypedDataset;
import com.openstudy.domainlens.api.ir.ValueType;

import static com.openstudy.modules.ai.service.OpenStudyAnalyses.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** #125: refinement body rules, conditions and change detection, and table pages (analysis-reuse-api.md). */
class AiReuseUnitTests {
    static final OpenStudyClock CLOCK = OpenStudyClock.fixed(OpenStudyClock.DEFAULT_AS_OF);

    static String code(Executable call) {
        return assertThrows(ApiException.class, call).error("t").code();
    }

    static Map<String, String> fields(Executable call) {
        return assertThrows(ApiException.class, call).error("t").fieldErrors();
    }

    static Map<String, Object> period(String from, String to) {
        return Map.of("period", Map.of("from", from, "to", to));
    }

    @Test void everyRegisteredAnalysisHasItsEditableConditions() {
        assertThat(AiReuse.EDITABLE.keySet()).isEqualTo(OpenStudyAnalyses.REGISTERED);
        // One-branch analyses are the same ones the question flow treats as one-branch.
        AiReuse.EDITABLE.forEach((id, editable) -> assertThat(editable.singleBranch()).as(id).isEqualTo(SINGLE_BRANCH.contains(id)));
        assertThat(AiReuse.editable(ID_POC_004).toMap()).containsEntry("period", null).containsEntry("products", false)
                .containsEntry("branches", Map.of("single", true));
        assertThat(AiReuse.editable(ID_POC_003).toMap()).containsEntry("period", Map.of("maxDays", 366)).containsEntry("products", true);
    }

    @Test void aRefinementTakesOnlyPeriodBranchesAndProducts() {
        var multi = AiReuse.editable(ID_POC_001);
        assertThat(code(() -> AiReuse.parse(Map.of(), multi))).isEqualTo("VALIDATION_FAILED");
        assertThat(code(() -> AiReuse.parse(null, multi))).isEqualTo("VALIDATION_FAILED");
        assertThat(fields(() -> AiReuse.parse(Map.of("analysisId", "x", "branchIds", List.of("1")), multi))).containsKey("analysisId");
        assertThat(fields(() -> AiReuse.parse(Map.of("clientRequestKey", "k1"), multi))).containsKey("period");
        assertThat(code(() -> AiReuse.parse(Map.of("branchIds", List.of()), multi))).isEqualTo("VALIDATION_FAILED");
        assertThat(code(() -> AiReuse.parse(Map.of("branchIds", List.of("0")), multi))).isEqualTo("VALIDATION_FAILED");
        assertThat(code(() -> AiReuse.parse(Map.of("branchIds", "1"), multi))).isEqualTo("VALIDATION_FAILED");
        assertThat(code(() -> AiReuse.parse(period("2026-10-31", "2026-10-01"), multi))).isEqualTo("VALIDATION_FAILED");
        assertThat(code(() -> AiReuse.parse(period("2026/10/01", "2026-10-31"), multi))).isEqualTo("VALIDATION_FAILED");
        assertThat(code(() -> AiReuse.parse(Map.of("period", Map.of("from", "2026-10-01")), multi))).isEqualTo("VALIDATION_FAILED");
        assertThat(code(() -> AiReuse.parse(Map.of("branchIds", List.of("1"), "clientRequestKey", "a b"), multi)))
                .isEqualTo("VALIDATION_FAILED");

        var parsed = AiReuse.parse(Map.of("branchIds", List.of("2", 1, "2"), "clientRequestKey", " k-1 "), multi);
        assertThat(parsed.branchIds()).containsExactly(2L, 1L);
        assertThat(parsed.productIds()).isNull();
        assertThat(parsed.from()).isNull();
        assertThat(parsed.clientRequestKey()).isEqualTo("k-1");
    }

    @Test void conditionsAnAnalysisDoesNotOfferAreNotEditable() {
        var realtime = AiReuse.editable(ID_POC_004);
        assertThat(code(() -> AiReuse.parse(period("2026-10-01", "2026-10-02"), realtime))).isEqualTo("FILTER_NOT_EDITABLE");
        assertThat(fields(() -> AiReuse.parse(period("2026-10-01", "2026-10-02"), realtime))).containsKey("period");
        assertThat(code(() -> AiReuse.parse(Map.of("productIds", List.of("1")), AiReuse.editable(ID_POC_001))))
                .isEqualTo("FILTER_NOT_EDITABLE");
        // A one-branch analysis takes exactly one branch.
        assertThat(code(() -> AiReuse.parse(Map.of("branchIds", List.of("1", "2")), realtime))).isEqualTo("VALIDATION_FAILED");
        assertThat(AiReuse.parse(Map.of("branchIds", List.of("2")), realtime).branchIds()).containsExactly(2L);
        // Products: an empty list is every product; too many are refused.
        var passes = AiReuse.editable(ID_POC_003);
        assertThat(AiReuse.parse(Map.of("productIds", List.of()), passes).productIds()).isEmpty();
        var many = new ArrayList<String>();
        for (int i = 1; i <= AiReuse.MAX_PRODUCTS + 1; i++) many.add(Integer.toString(i));
        assertThat(code(() -> AiReuse.parse(Map.of("productIds", many), passes))).isEqualTo("VALIDATION_FAILED");
    }

    @Test void aPeriodLongerThanTheAnalysisMaximumIsRefused() {
        var rooms = AiReuse.editable(ID_POC_005); // 31 days
        AiReuse.checkLength(AiReuse.parse(period("2026-10-01", "2026-10-31"), rooms), rooms);
        assertThat(code(() -> AiReuse.checkLength(AiReuse.parse(period("2026-10-01", "2026-11-01"), rooms), rooms)))
                .isEqualTo("PERIOD_TOO_LONG");
        AiReuse.checkLength(AiReuse.parse(Map.of("branchIds", List.of("1")), rooms), rooms);
    }

    @Test void conditionsShowThePeriodAsInclusiveDatesAndTellWhatChanged() {
        var lastMonth = AiReuse.Conditions.of(ID_POC_003, CLOCK.lastMonth(), false, Set.of(1L, 2L), Set.of(), "processed", null);
        assertThat(lastMonth.from()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(lastMonth.to()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(lastMonth.toMap()).containsEntry("period", Map.of("from", "2026-10-01", "to", "2026-10-31"))
                .containsEntry("periodSource", "DEFAULT").containsEntry("branchIds", List.of("1", "2"))
                .containsEntry("productIds", List.of()).containsEntry("zeroFill", null);
        // POC-006 looks seven days ahead from now: its end is not midnight, so that day is included.
        var expiring = AiReuse.Conditions.of(ID_POC_006, CLOCK.next7Days(), false, Set.of(1L), Set.of(), null, null);
        assertThat(expiring.to()).isEqualTo(expiring.from().plusDays(7));
        var realtime = AiReuse.Conditions.of(ID_POC_004, null, false, Set.of(1L), Set.of(), null, null);
        assertThat(realtime.toMap()).containsEntry("period", null).containsEntry("periodSource", null);

        var editable = AiReuse.editable(ID_POC_003);
        var sameDates = lastMonth.with(AiReuse.parse(period("2026-10-01", "2026-10-31"), editable));
        assertThat(sameDates.changedFrom(lastMonth)).isEmpty();
        assertThat(sameDates.periodEdited()).isTrue();
        var narrowed = lastMonth.with(AiReuse.parse(Map.of("branchIds", List.of("1"), "productIds", List.of("7")), editable));
        assertThat(narrowed.changedFrom(lastMonth)).containsExactly("BRANCHES", "PRODUCTS");
        assertThat(narrowed.periodEdited()).isFalse();
        assertThat(narrowed.from()).isEqualTo(lastMonth.from());
        var context = AiReuse.refinementContext("exec-1", narrowed, narrowed.changedFrom(lastMonth));
        assertThat(context).containsEntry("basedOnExecutionId", "exec-1").containsEntry("reset", false)
                .containsEntry("resetReason", null).containsEntry("changed", List.of("BRANCHES", "PRODUCTS"));
        assertThat(context.get("inherited")).isEqualTo(List.of("ANALYSIS", "PERIOD", "REFUND_BASIS"));
    }

    static ResultEnvelope envelope() {
        Instant now = OpenStudyClock.DEFAULT_AS_OF;
        var columns = List.of(new TypedDataset.Column("name", ValueType.STRING, null, null),
                new TypedDataset.Column("net_krw", ValueType.INTEGER, "KRW", 0));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 45; i++) rows.add(Map.of("name", "r" + i, "net_krw", (long) (i % 7) * 1000));
        Map<String, Object> blank = new HashMap<>();
        blank.put("name", "blank");
        blank.put("net_krw", null);
        rows.add(blank);
        var main = new TypedDataset("main", columns, rows, rows.size(), 0, 10, now, now);
        var totals = new TypedDataset("totals", columns, List.of(Map.of("name", "all", "net_krw", 1L)), 1, 0, 1, now, now);
        return new ResultEnvelope("req", "exec", "COMPLETED", List.of(main, totals), null, List.of(), null, now, "v", "p");
    }

    @Test void withoutATableQueryTheResultComesBackWhole() {
        var whole = envelope();
        assertThat(AiReuse.page(whole, null, null, null, null, null)).isSameAs(whole);
    }

    @Test void aTablePageSortsOneDatasetWithinTheWholeResult() {
        var paged = AiReuse.page(envelope(), "main", 2, 20, "net_krw", null);
        var main = paged.datasets().getFirst();
        assertThat(main.rows()).hasSize(20);
        assertThat(main.totalElements()).isEqualTo(46);
        assertThat(main.page()).isEqualTo(2);
        assertThat(main.size()).isEqualTo(20);
        // desc by default; the order holds across pages.
        var first = AiReuse.page(envelope(), "main", 1, 20, "net_krw", null).datasets().getFirst();
        assertThat((Long) first.rows().getFirst().get("net_krw")).isEqualTo(6000L);
        assertThat((Long) first.rows().getLast().get("net_krw")).isGreaterThanOrEqualTo((Long) main.rows().getFirst().get("net_krw"));
        // Missing values go last in either direction.
        var last = AiReuse.page(envelope(), "main", 3, 20, "net_krw", "asc").datasets().getFirst();
        assertThat(last.rows()).hasSize(6);
        assertThat(last.rows().getLast().get("name")).isEqualTo("blank");
        assertThat(AiReuse.page(envelope(), "main", 1, 50, "net_krw", "asc").datasets().getFirst().rows().getFirst().get("net_krw"))
                .isEqualTo(0L);
        // The other dataset is untouched; without datasetId the first one is paged.
        assertThat(paged.datasets().get(1).rows()).hasSize(1);
        assertThat(AiReuse.page(envelope(), null, null, null, null, null).datasets().getFirst().rows()).hasSize(46);
        assertThat(AiReuse.page(envelope(), null, 1, null, null, null).datasets().getFirst().rows()).hasSize(20);
        assertThat(AiReuse.page(envelope(), "main", 9, 20, null, null).datasets().getFirst().rows()).isEmpty();
    }

    @Test void anUnknownDatasetColumnOrPageIsAValidationError() {
        assertThat(fields(() -> AiReuse.page(envelope(), "nope", 1, 20, null, null))).containsKey("datasetId");
        assertThat(fields(() -> AiReuse.page(envelope(), "main", 1, 20, "secret", null))).containsKey("sort");
        assertThat(fields(() -> AiReuse.page(envelope(), "main", 0, 20, null, null))).containsKey("page");
        assertThat(fields(() -> AiReuse.page(envelope(), "main", 1, 51, null, null))).containsKey("size");
        assertThat(fields(() -> AiReuse.page(envelope(), "main", 1, 20, null, "up"))).containsKey("direction");
    }
}
