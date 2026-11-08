package com.openstudy.modules.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.openstudy.domainlens.api.ir.ResultRules;
import com.openstudy.domainlens.api.ir.TypedDataset;
import com.openstudy.domainlens.api.ir.ViewSpec;
import com.openstudy.modules.ai.service.OpenStudyAnalyses;
import com.openstudy.modules.ai.service.OpenStudyAnalyses.BranchRoomReservationSummary;
import com.openstudy.modules.ai.service.OpenStudyAnalyses.EmployeeAttendanceComparisonRow;
import com.openstudy.modules.ai.service.OpenStudyAnalyses.HeatmapCell;
import com.openstudy.modules.ai.service.OpenStudyAnalyses.TopPassProductRow;
import com.openstudy.modules.ai.service.OpenStudyAnalyses.UrgentTaskCandidate;
import com.openstudy.modules.ai.service.OpenStudyClock;
import com.openstudy.modules.sales.service.SalesAggregationService;
import com.openstudy.modules.sales.service.SalesAggregationService.BranchSalesRow;
import com.openstudy.modules.sales.service.SalesAggregationService.DailySalesRow;
import com.openstudy.modules.sales.service.SalesAggregationService.PurposeSalesRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration tests verifying M9 {@link SalesAggregationService} and M10 {@link OpenStudyAnalyses}
 * (`POC-001` ~ `POC-010`) against the live MySQL 8.4 dataset and golden expectations in
 * {@code docs/04_TEST_DATA/expected_aggregations.json} and {@code docs/05_TEST_CASES/expected_viewspec.json}
 * ({@code OS-DL-0407}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/openstudy?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "spring.datasource.username=openstudy_app",
        "spring.datasource.password=${OPENSTUDY_AI_TEST_DB_PASSWORD:}",
        "spring.flyway.enabled=true",
        "spring.flyway.url=jdbc:mysql://127.0.0.1:3307/openstudy?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        "spring.flyway.user=openstudy_app",
        "spring.flyway.password=${OPENSTUDY_AI_TEST_DB_PASSWORD:}",
        "openstudy.ai.fixed-as-of=2026-11-02T05:00:00Z"
})
@EnabledIf("com.openstudy.domainlens.LocalAiFixtures#localAiTestsEnabled")
class OpenStudyGoldenAggregationsIntegrationTests {
    private static final List<Long> ALL_BRANCHES = List.of(9100001L, 9100002L, 9100003L);
    private static JsonNode goldenCases;
    private static JsonNode expectedViewSpecs;

    @Autowired
    private OpenStudyClock clock;

    @Autowired
    /** The demo data's active OWNER (sales:read on every branch, which the M9 services check). */
    static final long OWNER = 1L;

    private SalesAggregationService salesService;

    @Autowired
    private OpenStudyAnalyses analyses;

    @BeforeAll
    static void loadGoldenJsonFiles() throws Exception {
        var mapper = JsonMapper.builder().build();
        Path aggPath = resolveDocPath("docs/04_TEST_DATA/expected_aggregations.json");
        Path viewPath = resolveDocPath("docs/05_TEST_CASES/expected_viewspec.json");
        goldenCases = mapper.readTree(Files.readString(aggPath)).get("cases");
        expectedViewSpecs = mapper.readTree(Files.readString(viewPath)).get("cases");
    }

    private static Path resolveDocPath(String relative) {
        for (Path candidate : List.of(
                Path.of(relative),
                Path.of("..", relative)
        )) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Golden test file not found: " + relative);
    }

    @Test
    void openStudyClockResolvesFixedReferenceAndKstRelativePeriods() {
        assertThat(clock.asOf()).isEqualTo(Instant.parse("2026-11-02T05:00:00Z"));
        assertThat(clock.asOfOffsetKst()).isEqualTo(OffsetDateTime.parse("2026-11-02T14:00:00+09:00"));

        var thisMonth = clock.thisMonth();
        assertThat(thisMonth.startKst()).isEqualTo(OffsetDateTime.parse("2026-11-01T00:00:00+09:00"));
        assertThat(thisMonth.endExclusiveKst()).isEqualTo(OffsetDateTime.parse("2026-11-03T00:00:00+09:00"));

        var lastMonth = clock.lastMonth();
        assertThat(lastMonth.startKst()).isEqualTo(OffsetDateTime.parse("2026-10-01T00:00:00+09:00"));
        assertThat(lastMonth.endExclusiveKst()).isEqualTo(OffsetDateTime.parse("2026-11-01T00:00:00+09:00"));

        var thisWeek = clock.thisWeek();
        assertThat(thisWeek.startKst()).isEqualTo(OffsetDateTime.parse("2026-11-02T00:00:00+09:00"));
        assertThat(thisWeek.endExclusiveKst()).isEqualTo(OffsetDateTime.parse("2026-11-09T00:00:00+09:00"));

        var lastWeek = clock.lastWeek();
        assertThat(lastWeek.startKst()).isEqualTo(OffsetDateTime.parse("2026-10-26T00:00:00+09:00"));
        assertThat(lastWeek.endExclusiveKst()).isEqualTo(OffsetDateTime.parse("2026-11-02T00:00:00+09:00"));

        var next7Days = clock.next7Days();
        assertThat(next7Days.startKst()).isEqualTo(OffsetDateTime.parse("2026-11-02T14:00:00+09:00"));
        assertThat(next7Days.endExclusiveKst()).isEqualTo(OffsetDateTime.parse("2026-11-09T14:00:00+09:00"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void poc001BranchSalesComparisonAndM9ParityMatchGoldenValues() {
        var result = analyses.executePoc001BranchSales(ALL_BRANCHES, OWNER);
        assertValidEnvelopeAndViewTypes("POC-001", result);

        JsonNode expected = goldenCases.get("POC-001").get("expected");
        var branchRows = (List<BranchSalesRow>) result.summary().get("branch_rows");
        assertThat(branchRows).hasSize(3);

        for (int i = 0; i < 3; i++) {
            JsonNode expRow = expected.get("branches").get(i);
            BranchSalesRow actual = branchRows.get(i);
            assertThat(actual.branchId()).isEqualTo(expRow.get("branch_id").asLong());
            assertThat(actual.branchName()).isEqualTo(expRow.get("branch").asString());
            assertThat(actual.approvedKrw()).isEqualTo(expRow.get("approved_krw").asLong());
            assertThat(actual.refundKrw()).isEqualTo(expRow.get("refund_krw").asLong());
            assertThat(actual.netKrw()).isEqualTo(expRow.get("net_krw").asLong());
            assertThat(actual.approvedCount()).isEqualTo(expRow.get("approved_count").asLong());
            assertThat(actual.refundCount()).isEqualTo(expRow.get("refund_count").asLong());
        }

        var totals = (Map<String, Long>) result.summary().get("totals");
        assertThat(totals.get("approved_krw")).isEqualTo(expected.get("totals").get("approved_krw").asLong());
        assertThat(totals.get("refund_krw")).isEqualTo(expected.get("totals").get("refund_krw").asLong());
        assertThat(totals.get("net_krw")).isEqualTo(expected.get("totals").get("net_krw").asLong());

        var contrastRows = (List<BranchSalesRow>) result.summary().get("original_payment_date_basis_contrast");
        assertThat(contrastRows).hasSize(3);
        assertThat(contrastRows.get(0).branchId()).isEqualTo(9_100_001L);
        assertThat(contrastRows.get(0).approvedKrw()).isEqualTo(3_320_000L);
        assertThat(contrastRows.get(0).refundKrw()).isEqualTo(99_000L);
        assertThat(contrastRows.get(0).netKrw()).isEqualTo(3_221_000L);
        assertThat(contrastRows.get(1).branchId()).isEqualTo(9_100_002L);
        assertThat(contrastRows.get(1).approvedKrw()).isEqualTo(2_206_000L);
        assertThat(contrastRows.get(1).refundKrw()).isEqualTo(28_000L);
        assertThat(contrastRows.get(1).netKrw()).isEqualTo(2_178_000L);
        assertThat(contrastRows.get(2).branchId()).isEqualTo(9_100_003L);
        assertThat(contrastRows.get(2).netKrw()).isEqualTo(0L);

        // The numbers come from the M9 sales API's own service (M10-D05, #95): same totals as the analysis rows.
        var m9 = salesService.aggregateByBranch(OWNER, ALL_BRANCHES, java.time.Instant.parse("2026-10-31T15:00:00Z"),
                java.time.Instant.parse("2026-11-02T15:00:00Z"), SalesAggregationService.RefundBasis.REFUND_PROCESSED_AT);
        assertThat(m9.totalApprovedKrw()).isEqualTo(5_526_000L);
        assertThat(m9.totalRefundKrw()).isEqualTo(187_000L);
        assertThat(m9.totalNetKrw()).isEqualTo(5_339_000L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void poc002GangnamHourlyCongestionMatchesGoldenHeatmapAndPeaks() {
        var result = analyses.executePoc002HourlyCongestion(9100001L);
        assertValidEnvelopeAndViewTypes("POC-002", result);

        JsonNode expected = goldenCases.get("POC-002").get("expected");
        var cells = (List<HeatmapCell>) result.summary().get("sampled_heatmap_rows");
        assertThat(cells).hasSize(35);

        for (int i = 0; i < 35; i++) {
            JsonNode expCell = expected.get("sampled_heatmap_rows").get(i);
            HeatmapCell actual = cells.get(i);
            assertThat(actual.weekday()).isEqualTo(expCell.get("weekday").asInt());
            assertThat(actual.hourKst()).isEqualTo(expCell.get("hour_kst").asInt());
            assertThat(actual.occupiedSeatSamples()).isEqualTo(expCell.get("occupied_seat_samples").asLong());
            assertThat(actual.seatSampleCapacity()).isEqualTo(expCell.get("seat_sample_capacity").asLong());
            assertThat(actual.sampledOccupancyPct()).isEqualTo(expCell.get("sampled_occupancy_pct").asDouble());
        }

        var peaks = (List<HeatmapCell>) result.summary().get("peak_cells");
        assertThat(peaks).hasSize(4);
        for (int i = 0; i < 4; i++) {
            JsonNode expPeak = expected.get("peak_cells").get(i);
            HeatmapCell actual = peaks.get(i);
            assertThat(actual.weekday()).isEqualTo(expPeak.get("weekday").asInt());
            assertThat(actual.hourKst()).isEqualTo(expPeak.get("hour_kst").asInt());
            assertThat(actual.occupiedSeatSamples()).isEqualTo(36L);
            assertThat(actual.seatSampleCapacity()).isEqualTo(46L);
            assertThat(actual.sampledOccupancyPct()).isEqualTo(78.3);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void poc003Top5PassesMatchesGoldenRankingAndCounts() {
        var result = analyses.executePoc003Top5Passes(ALL_BRANCHES, OWNER);
        assertValidEnvelopeAndViewTypes("POC-003", result);

        JsonNode expected = goldenCases.get("POC-003").get("expected");
        var top5 = (List<TopPassProductRow>) result.summary().get("top5");
        assertThat(top5).hasSize(5);

        for (int i = 0; i < 5; i++) {
            JsonNode expRow = expected.get("top5").get(i);
            TopPassProductRow actual = top5.get(i);
            assertThat(actual.productId()).isEqualTo(expRow.get("product_id").asLong());
            assertThat(actual.product()).isEqualTo(expRow.get("product").asString());
            assertThat(actual.issuedUnits()).isEqualTo(expRow.get("issued_units").asLong());
            assertThat(actual.refundedUnits()).isEqualTo(expRow.get("refunded_units").asLong());
            assertThat(actual.netUnits()).isEqualTo(expRow.get("net_units").asLong());
        }

        // Verify exact net KRW values from 04-m9-m10-metrics-and-analyzers.md
    }

    @Test
    void poc004GangnamRealtimeSeatsMatchesGoldenAvailability() {
        var result = analyses.executePoc004RealtimeSeats(9100001L);
        assertValidEnvelopeAndViewTypes("POC-004", result);

        JsonNode expected = goldenCases.get("POC-004").get("expected");
        Map<String, Object> summary = result.summary();
        assertThat(summary.get("branch_id")).isEqualTo(expected.get("branch_id").asLong());
        assertThat(summary.get("total")).isEqualTo(expected.get("total").asLong());
        assertThat(summary.get("available")).isEqualTo(expected.get("available").asLong());
        assertThat(summary.get("occupied")).isEqualTo(expected.get("occupied").asLong());
        assertThat(summary.get("free")).isEqualTo(expected.get("free").asLong());
        assertThat(summary.get("blocked")).isEqualTo(expected.get("blocked").asLong());
        assertThat(summary.get("occupied_pct")).isEqualTo(expected.get("occupied_pct").asDouble());
    }

    @Test
    @SuppressWarnings("unchecked")
    void poc005WeeklyRoomReservationsMatchesGoldenBranchSummaries() {
        var result = analyses.executePoc005WeeklyRoomReservations(ALL_BRANCHES);
        assertValidEnvelopeAndViewTypes("POC-005", result);

        JsonNode expected = goldenCases.get("POC-005").get("expected");
        Map<String, Object> summary = result.summary();
        assertThat(summary.get("all_bookings")).isEqualTo(expected.get("all_bookings").asLong());
        assertThat(summary.get("cancelled")).isEqualTo(expected.get("cancelled").asLong());
        assertThat(summary.get("active_bookings")).isEqualTo(78L);
        assertThat(summary.get("total_billable_booking_amount_krw")).isEqualTo(1_716_000L);

        var branchSummaries = (Map<String, BranchRoomReservationSummary>) summary.get("branch_summaries");
        for (String branchKey : List.of("9100001", "9100002", "9100003")) {
            JsonNode expBranch = expected.get("branch_summaries").get(branchKey);
            BranchRoomReservationSummary actual = branchSummaries.get(branchKey);
            assertThat(actual.total()).isEqualTo(expBranch.get("total").asLong());
            assertThat(actual.billableBookingAmountKrw()).isEqualTo(expBranch.get("billable_booking_amount_krw").asLong());
            JsonNode expByStatus = expBranch.get("by_status");
            for (var prop : expByStatus.properties()) {
                assertThat(actual.byStatus().get(prop.getKey())).isEqualTo(prop.getValue().asLong());
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void poc006ExpiringPassesMatchesGoldenCountAndIdsWithZeroPii() {
        var result = analyses.executePoc006ExpiringPasses(ALL_BRANCHES);
        assertValidEnvelopeAndViewTypes("POC-006", result);

        JsonNode expected = goldenCases.get("POC-006").get("expected");
        Map<String, Object> summary = result.summary();
        assertThat(summary.get("count")).isEqualTo(expected.get("count").asLong());

        var byBranch = (Map<String, Long>) summary.get("by_branch");
        assertThat(byBranch.get("9100001")).isEqualTo(expected.get("by_branch").get("9100001").asLong());
        assertThat(byBranch.get("9100002")).isEqualTo(expected.get("by_branch").get("9100002").asLong());
        assertThat(byBranch.get("9100003")).isEqualTo(expected.get("by_branch").get("9100003").asLong());

        var actualPassIds = (List<Long>) summary.get("pass_ids");
        List<Long> expectedPassIds = new ArrayList<>();
        for (JsonNode idNode : expected.get("pass_ids")) {
            expectedPassIds.add(idNode.asLong());
        }
        assertThat(actualPassIds).containsExactlyElementsOf(expectedPassIds);

        // Verify zero customer PII columns in TypedDataset
        TypedDataset ds = result.envelope().datasets().getFirst();
        List<String> columnAliases = ds.columns().stream().map(TypedDataset.Column::alias).toList();
        assertThat(columnAliases).doesNotContain("customer_id", "customer_name", "phone", "email", "member_no");
    }

    @Test
    @SuppressWarnings("unchecked")
    void poc007DailySalesTrendMatchesAll31GoldenDaysWithZeroFill() {
        var result = analyses.executePoc007DailySalesTrend(ALL_BRANCHES, OWNER);
        assertValidEnvelopeAndViewTypes("POC-007", result);

        JsonNode expected = goldenCases.get("POC-007").get("expected");
        Map<String, Object> summary = result.summary();
        assertThat(summary.get("zero_filled")).isEqualTo(true);
        assertThat(summary.get("days_count")).isEqualTo(expected.get("days_count").asInt());
        assertThat(summary.get("total_net_krw")).isEqualTo(expected.get("total_net_krw").asLong());
        assertThat(summary.get("total_approved_krw")).isEqualTo(30_445_000L);
        assertThat(summary.get("total_refund_krw")).isEqualTo(746_000L);

        var days = (List<DailySalesRow>) summary.get("days");
        assertThat(days).hasSize(31);
        for (int i = 0; i < 31; i++) {
            JsonNode expDay = expected.get("days").get(i);
            DailySalesRow actual = days.get(i);
            assertThat(actual.dateKst()).isEqualTo(expDay.get("date_kst").asString());
            assertThat(actual.approvedKrw()).isEqualTo(expDay.get("approved_krw").asLong());
            assertThat(actual.refundKrw()).isEqualTo(expDay.get("refund_krw").asLong());
            assertThat(actual.netKrw()).isEqualTo(expDay.get("net_krw").asLong());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void poc008SalesByItemTypeMatchesGoldenBreakdownAndWinner() {
        var result = analyses.executePoc008SalesByItemType(ALL_BRANCHES, OWNER);
        assertValidEnvelopeAndViewTypes("POC-008", result);

        JsonNode expected = goldenCases.get("POC-008").get("expected");
        Map<String, Object> summary = result.summary();
        assertThat(summary.get("winner_purpose")).isEqualTo(expected.get("winner_purpose").asString());
        assertThat(summary.get("ranking_metric")).isEqualTo(expected.get("ranking_metric").asString());

        var rows = (List<PurposeSalesRow>) summary.get("purpose_rows");
        assertThat(rows).hasSize(3);
        for (int i = 0; i < 3; i++) {
            JsonNode expRow = expected.get("purpose_rows").get(i);
            PurposeSalesRow actual = rows.get(i);
            assertThat(actual.purpose()).isEqualTo(expRow.get("purpose").asString());
            assertThat(actual.approvedKrw()).isEqualTo(expRow.get("approved_krw").asLong());
            assertThat(actual.refundKrw()).isEqualTo(expRow.get("refund_krw").asLong());
            assertThat(actual.netKrw()).isEqualTo(expRow.get("net_krw").asLong());
            assertThat(actual.approvedCount()).isEqualTo(expRow.get("approved_count").asLong());
            assertThat(actual.refundCount()).isEqualTo(expRow.get("refund_count").asLong());
        }
        assertThat(rows.get(0).sharePct()).isEqualTo(95.3);
        assertThat(rows.get(1).sharePct()).isEqualTo(3.6);
        assertThat(rows.get(2).sharePct()).isEqualTo(1.1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void poc009UrgentTasksMatchesProvisionalCandidatesAndHidesTaskBody() {
        var result = analyses.executePoc009UrgentTasks(ALL_BRANCHES);
        assertValidEnvelopeAndViewTypes("POC-009", result);

        JsonNode expected = goldenCases.get("POC-009").get("expected");
        Map<String, Object> summary = result.summary();
        assertThat(summary.get("status")).isEqualTo("PROVISIONAL_NOT_GOLDEN");
        assertThat(summary.get("candidate_count")).isEqualTo(expected.get("candidate_count").asInt());

        var candidates = (List<UrgentTaskCandidate>) summary.get("illustrative_due_today_or_earlier_and_high_or_urgent");
        assertThat(candidates).hasSize(4);
        for (int i = 0; i < 4; i++) {
            JsonNode expTask = expected.get("illustrative_due_today_or_earlier_and_high_or_urgent").get(i);
            UrgentTaskCandidate actual = candidates.get(i);
            assertThat(actual.id()).isEqualTo(expTask.get("id").asLong());
            assertThat(actual.title()).isEqualTo(expTask.get("title").asString());
            assertThat(actual.branchId()).isEqualTo(expTask.get("branch_id").asLong());
            assertThat(actual.priority()).isEqualTo(expTask.get("priority").asString());
        }

        // Verify work_task.body is never exposed in TypedDataset
        TypedDataset ds = result.envelope().datasets().getFirst();
        List<String> columnAliases = ds.columns().stream().map(TypedDataset.Column::alias).toList();
        assertThat(columnAliases).doesNotContain("body");
    }

    @Test
    @SuppressWarnings("unchecked")
    void poc010WeeklyAttendanceComparisonMatchesRawAndCorrectedMinutes() {
        var result = analyses.executePoc010WeeklyAttendance(ALL_BRANCHES);
        assertValidEnvelopeAndViewTypes("POC-010", result);

        JsonNode expected = goldenCases.get("POC-010").get("expected");
        Map<String, Object> summary = result.summary();
        assertThat(summary.get("status")).isEqualTo("PROVISIONAL_NOT_GOLDEN");
        assertThat(summary.get("illustrative_correction_delta_minutes"))
                .isEqualTo(expected.get("illustrative_correction_delta_minutes").asLong());

        var empRows = (List<EmployeeAttendanceComparisonRow>) summary.get("employee_rows");
        assertThat(empRows).hasSize(4);
        for (int i = 0; i < 4; i++) {
            JsonNode expEmp = expected.get("employee_rows").get(i);
            EmployeeAttendanceComparisonRow actual = empRows.get(i);
            assertThat(actual.employeeId()).isEqualTo(expEmp.get("employee_id").asLong());
            assertThat(actual.branchId()).isEqualTo(expEmp.get("branch_id").asLong());
            assertThat(actual.shiftDays()).isEqualTo(expEmp.get("shift_days").asLong());
            assertThat(actual.rawWorkMinutes()).isEqualTo(expEmp.get("raw_work_minutes").asLong());
            assertThat(actual.illustrativeCorrectedMinutes()).isEqualTo(expEmp.get("illustrative_corrected_minutes").asLong());
        }
    }

    @Test
    void naturalLanguageQuestionRouterResolvesAllTenPocQuestions() {
        for (int i = 1; i <= 10; i++) {
            String pocCode = String.format("POC-%03d", i);
            String question = goldenCases.get(pocCode).get("question").asString();
            var executed = analyses.executeCase(question, ALL_BRANCHES, OWNER);
            assertThat(executed.pocCode()).isEqualTo(pocCode);
        }
    }

    private static void assertValidEnvelopeAndViewTypes(String pocCode, OpenStudyAnalyses.PocExecutionResult result) {
        ResultRules.envelope(result.envelope());
        assertThat(result.envelope().view()).isNotNull();

        List<String> actualComponentTypes = result.envelope().view().components().stream()
                .map(c -> c.componentRef().id())
                .toList();

        List<String> expectedTypes = new ArrayList<>();
        for (JsonNode node : expectedViewSpecs.get(pocCode).get("target_components")) {
            expectedTypes.add(node.asString());
        }
        assertThat(Set.copyOf(actualComponentTypes)).containsAll(expectedTypes);
    }
}
