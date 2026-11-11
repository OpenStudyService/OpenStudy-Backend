package com.openstudy.modules.ai.service;

import com.openstudy.domainlens.api.error.EngineErrorCode;
import com.openstudy.domainlens.api.error.EngineException;
import com.openstudy.domainlens.api.formula.FormulaDefinition;
import com.openstudy.domainlens.api.ir.ComponentCatalog;
import com.openstudy.domainlens.api.ir.ContractRules;
import com.openstudy.domainlens.api.ir.Layout;
import com.openstudy.domainlens.api.ir.OutcomeNotice;
import com.openstudy.domainlens.api.ir.QuerySpec;
import com.openstudy.domainlens.api.ir.ResultEnvelope;
import com.openstudy.domainlens.api.ir.ResultRules;
import com.openstudy.domainlens.api.ir.TypedDataset;
import com.openstudy.domainlens.api.ir.ValueType;
import com.openstudy.domainlens.api.ir.ViewSpec;
import com.openstudy.domainlens.api.spi.AnalysisExecutor;
import com.openstudy.domainlens.api.spi.UserContext;
import com.openstudy.domainlens.api.work.Cancellation;
import com.openstudy.domainlens.api.work.ExecutionPlan;
import com.openstudy.domainlens.catalog.SemanticRegistry;
import com.openstudy.domainlens.query.ViewBinder;
import com.openstudy.modules.sales.service.SalesAggregationService;
import com.openstudy.modules.sales.service.SalesAggregationService.RefundBasis;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumer-owned DomainLens analyses for OpenStudy PoC-001 through PoC-010 ({@code OS-DL-0403} ~ {@code OS-DL-0406}).
 * <p>
 * All sales metrics ({@code POC-001}, {@code POC-007}, {@code POC-008}) delegate to {@link SalesAggregationService}
 * so that M9 ({@code /api/sales}) and M10 (DomainLens AI) share 100% identical calculation logic ({@code ADR-M9-001}, {@code ADR-M10-001}).
 */
@Service
@Transactional(readOnly = true)
public class OpenStudyAnalyses implements AnalysisExecutor {
    public static final String CATALOG_VERSION = "openstudy-ui-1.0.0";
    public static final String COMPONENT_VERSION = "1.0.0";
    /** Analyses that look at one branch only; a wider scope asks which branch (2026-11-08 decision, ADR-M10-003). */
    public static final java.util.Set<String> SINGLE_BRANCH = java.util.Set.of("openstudy.seats.hourlyCongestion",
            "openstudy.seats.realtimeAvailability");

    public static final String ID_POC_001 = "openstudy.sales.branchComparison";
    public static final String ID_POC_002 = "openstudy.seats.hourlyCongestion";
    public static final String ID_POC_003 = "openstudy.passes.topProducts";
    public static final String ID_POC_004 = "openstudy.seats.realtimeAvailability";
    public static final String ID_POC_005 = "openstudy.rooms.weeklyReservations";
    public static final String ID_POC_006 = "openstudy.passes.expiringSoon";
    public static final String ID_POC_007 = "openstudy.sales.dailyTrend";
    public static final String ID_POC_008 = "openstudy.sales.byItemType";
    public static final String ID_POC_009 = "openstudy.tasks.urgentTodo";
    public static final String ID_POC_010 = "openstudy.attendance.weeklyWorkMinutes";

    public static final List<Integer> SAMPLED_HOURS_KST = List.of(9, 12, 15, 18, 21);

    // Structured records for Golden Aggregation verification and ViewSpec binding
    public record PocExecutionResult(
            String pocCode,
            String analysisId,
            String question,
            String semanticStatus,
            List<String> expectedViewTypes,
            ResultEnvelope envelope,
            Map<String, Object> summary
    ) {}

    public record HeatmapCell(
            int weekday,
            int hourKst,
            long occupiedSeatSamples,
            long seatSampleCapacity,
            double sampledOccupancyPct
    ) {}

    /** One ranked pass product, in M9's pass quantities (sold, refunded, net sold). */
    public record TopPassProductRow(long productId, String product, long issuedUnits, long refundedUnits, long netUnits) {}

    public record RealtimeSeatSummary(
            long branchId,
            long total,
            long available,
            long occupied,
            long free,
            long blocked,
            double occupiedPct
    ) {}

    public record BranchRoomReservationSummary(
            long branchId,
            String branchName,
            long total,
            Map<String, Long> byStatus,
            long activeBookings,
            long cancelledBookings,
            long billableBookingAmountKrw
    ) {}

    public record ExpiringPassItem(
            long passId,
            long branchId,
            String branchName,
            long productId,
            String productName,
            String passType,
            Long remainingMinutes,
            Instant expiresAt,
            String status
    ) {}

    public record UrgentTaskCandidate(
            long id,
            String title,
            long branchId,
            String branchName,
            String priority,
            String status,
            Instant dueAt,
            Long assigneeEmployeeId
    ) {}

    public record EmployeeAttendanceComparisonRow(
            long employeeId,
            String employeeName,
            long branchId,
            String branchName,
            long shiftDays,
            long rawWorkMinutes,
            long illustrativeCorrectedMinutes,
            long correctionDeltaMinutes
    ) {}

    private final EntityManager entityManager;
    private final SalesAggregationService salesService;
    private final OpenStudyClock clock;
    private final SemanticRegistry registry;
    private final com.openstudy.modules.branch.service.SessionBranchService branchNames;
    private final ViewBinder viewBinder;
    private final Map<String, Definition> definitions;
    private final Map<String, String> aliasToAnalysisId;

    public OpenStudyAnalyses(
            EntityManager entityManager,
            SalesAggregationService salesService,
            OpenStudyClock clock,
            SemanticRegistry registry,
            com.openstudy.modules.branch.service.SessionBranchService branchNames
    ) {
        this.entityManager = entityManager;
        this.branchNames = branchNames;
        this.salesService = salesService;
        this.clock = clock;
        this.registry = registry;
        this.viewBinder = new ViewBinder(ComponentCatalog.openStudyDefault());
        this.definitions = buildDefinitions();
        this.aliasToAnalysisId = Map.ofEntries(
                Map.entry("POC-001", ID_POC_001),
                Map.entry(ID_POC_001, ID_POC_001),
                Map.entry("sales.net_sales_krw", ID_POC_001),
                Map.entry("POC-002", ID_POC_002),
                Map.entry(ID_POC_002, ID_POC_002),
                Map.entry("seats.hourly_congestion", ID_POC_002),
                Map.entry("POC-003", ID_POC_003),
                Map.entry(ID_POC_003, ID_POC_003),
                Map.entry("passes.top_products", ID_POC_003),
                Map.entry("POC-004", ID_POC_004),
                Map.entry(ID_POC_004, ID_POC_004),
                Map.entry("seats.realtime_availability", ID_POC_004),
                Map.entry("POC-005", ID_POC_005),
                Map.entry(ID_POC_005, ID_POC_005),
                Map.entry("rooms.weekly_reservations", ID_POC_005),
                Map.entry("POC-006", ID_POC_006),
                Map.entry(ID_POC_006, ID_POC_006),
                Map.entry("passes.expiring_soon", ID_POC_006),
                Map.entry("POC-007", ID_POC_007),
                Map.entry(ID_POC_007, ID_POC_007),
                Map.entry("sales.daily_trend", ID_POC_007),
                Map.entry("POC-008", ID_POC_008),
                Map.entry(ID_POC_008, ID_POC_008),
                Map.entry("sales.by_item_type", ID_POC_008),
                Map.entry("POC-009", ID_POC_009),
                Map.entry(ID_POC_009, ID_POC_009),
                Map.entry("tasks.urgent_todo", ID_POC_009),
                Map.entry("POC-010", ID_POC_010),
                Map.entry(ID_POC_010, ID_POC_010),
                Map.entry("attendance.weekly_work_minutes", ID_POC_010)
        );
    }

    private static TypedDataset.Column integer(String alias, String unit) {
        return new TypedDataset.Column(alias, ValueType.INTEGER, unit, 0);
    }

    private static TypedDataset.Column krw(String alias) {
        return new TypedDataset.Column(alias, ValueType.INTEGER, "KRW", 0);
    }

    private static TypedDataset.Column decimal(String alias, String unit, int precision) {
        return new TypedDataset.Column(alias, ValueType.DECIMAL, unit, precision);
    }

    private static TypedDataset.Column string(String alias) {
        return new TypedDataset.Column(alias, ValueType.STRING, null, null);
    }

    private static TypedDataset.Column timestamp(String alias) {
        return new TypedDataset.Column(alias, ValueType.TIMESTAMP, null, null);
    }

    private static Map<String, Definition> buildDefinitions() {
        var defs = new LinkedHashMap<String, Definition>();

        defs.put(ID_POC_001, new Definition(
                ID_POC_001, "1", "payment", "payment",
                Map.of(
                        "branchSales", List.of(
                                integer("branch_id", null),
                                string("branch_name"),
                                krw("approved_krw"),
                                krw("refund_krw"),
                                krw("net_krw"),
                                integer("approved_count", "건"),
                                integer("refund_count", "건")
                        ),
                        "salesTotals", List.of(
                                krw("approved_krw"),
                                krw("refund_krw"),
                                krw("net_krw"),
                                integer("approved_count", "건"),
                                integer("refund_count", "건")
                        ),
                        "basisContrast", List.of(
                                integer("branch_id", null),
                                krw("approved_krw"),
                                krw("refund_krw"),
                                krw("net_krw")
                        )
                ),
                true
        ));

        defs.put(ID_POC_002, new Definition(
                ID_POC_002, "1", "seat_session", "seat_session",
                Map.of(
                        "heatmapRows", List.of(
                                integer("weekday", null),
                                integer("hour_kst", "시"),
                                integer("occupied_seat_samples", "석"),
                                integer("seat_sample_capacity", "석"),
                                decimal("sampled_occupancy_pct", "%", 1)
                        ),
                        "peakCells", List.of(
                                integer("weekday", null),
                                integer("hour_kst", "시"),
                                integer("occupied_seat_samples", "석"),
                                integer("seat_sample_capacity", "석"),
                                decimal("sampled_occupancy_pct", "%", 1)
                        )
                ),
                true
        ));

        defs.put(ID_POC_003, new Definition(
                ID_POC_003, "1", "pass", "pass",
                Map.of(
                        "topProducts", List.of(
                                integer("product_id", null),
                                string("product"),
                                integer("issued_units", "건"),
                                integer("refunded_units", "건"),
                                integer("net_units", "건")
                        )
                ),
                true
        ));

        defs.put(ID_POC_004, new Definition(
                ID_POC_004, "1", "seat", "seat",
                Map.of(
                        "seatSummary", List.of(
                                integer("branch_id", null),
                                integer("total", "석"),
                                integer("available", "석"),
                                integer("occupied", "석"),
                                integer("free", "석"),
                                integer("blocked", "석"),
                                decimal("occupied_pct", "%", 1)
                        ),
                        "seatMap", List.of(
                                integer("seat_id", null),
                                string("seat_no"),
                                string("seat_status"),
                                string("occupancy_state")
                        )
                ),
                true
        ));

        defs.put(ID_POC_005, new Definition(
                ID_POC_005, "1", "room_reservation", "room_reservation",
                Map.of(
                        "reservationRows", List.of(
                                integer("reservation_id", null),
                                integer("branch_id", null),
                                string("branch_name"),
                                integer("room_id", null),
                                string("room_name"),
                                timestamp("start_at"),
                                timestamp("end_at"),
                                string("status"),
                                krw("billable_amount_krw")
                        ),
                        "branchSummaries", List.of(
                                integer("branch_id", null),
                                string("branch_name"),
                                integer("total", "건"),
                                integer("confirmed_count", "건"),
                                integer("completed_count", "건"),
                                integer("cancelled_count", "건"),
                                krw("billable_booking_amount_krw")
                        ),
                        "reservationTotals", List.of(
                                integer("all_bookings", "건"),
                                integer("cancelled", "건"),
                                integer("active_bookings", "건"),
                                krw("billable_booking_amount_krw")
                        )
                ),
                true
        ));

        defs.put(ID_POC_006, new Definition(
                ID_POC_006, "1", "pass", "pass",
                Map.of(
                        "expiringPasses", List.of(
                                integer("pass_id", null),
                                integer("branch_id", null),
                                string("branch_name"),
                                integer("product_id", null),
                                string("product_name"),
                                string("pass_type"),
                                integer("remaining_minutes", "분"),
                                timestamp("expires_at"),
                                string("status")
                        ),
                        "expiringByBranch", List.of(
                                integer("branch_id", null),
                                string("branch_name"),
                                integer("expiring_count", "건")
                        ),
                        "expiringTotals", List.of(
                                integer("count", "건")
                        )
                ),
                true
        ));

        defs.put(ID_POC_007, new Definition(
                ID_POC_007, "1", "payment", "payment",
                Map.of(
                        "dailySales", List.of(
                                string("date_kst"),
                                krw("approved_krw"),
                                krw("refund_krw"),
                                krw("net_krw"),
                                integer("approved_count", "건"),
                                integer("refund_count", "건")
                        ),
                        "dailySalesTotals", List.of(
                                integer("days_count", "일"),
                                krw("approved_krw"),
                                krw("refund_krw"),
                                krw("total_net_krw")
                        )
                ),
                true
        ));

        defs.put(ID_POC_008, new Definition(
                ID_POC_008, "1", "payment", "payment",
                Map.of(
                        "purposeSales", List.of(
                                string("purpose"),
                                krw("approved_krw"),
                                krw("refund_krw"),
                                krw("net_krw"),
                                integer("approved_count", "건"),
                                integer("refund_count", "건"),
                                decimal("share_pct", "%", 1)
                        ),
                        "purposeWinner", List.of(
                                string("winner_purpose"),
                                string("ranking_metric"),
                                krw("winner_net_krw"),
                                krw("total_net_krw")
                        )
                ),
                true
        ));

        defs.put(ID_POC_009, new Definition(
                ID_POC_009, "1", "work_task", "work_task",
                Map.of(
                        "urgentTasks", List.of(
                                integer("id", null),
                                string("title"),
                                integer("branch_id", null),
                                string("branch_name"),
                                string("priority"),
                                string("status"),
                                timestamp("due_at"),
                                integer("assignee_employee_id", null)
                        ),
                        "taskPolicyMeta", List.of(
                                integer("candidate_count", "건"),
                                string("status")
                        )
                ),
                true
        ));

        defs.put(ID_POC_010, new Definition(
                ID_POC_010, "1", "attendance_record", "attendance_record",
                Map.of(
                        "employeeWorkHours", List.of(
                                integer("employee_id", null),
                                string("employee_name"),
                                integer("branch_id", null),
                                string("branch_name"),
                                integer("shift_days", "일"),
                                integer("raw_work_minutes", "분"),
                                integer("illustrative_corrected_minutes", "분"),
                                integer("correction_delta_minutes", "분")
                        ),
                        "attendancePolicyMeta", List.of(
                                integer("illustrative_correction_delta_minutes", "분"),
                                string("status")
                        )
                ),
                true
        ));

        return Map.copyOf(defs);
    }

    @Override
    public boolean supports(@NonNull String analysisId) {
        return definitions.containsKey(normalizeAnalysisId(analysisId));
    }

    @Override
    public @NonNull Set<String> analysisIds() {
        return definitions.keySet();
    }

    @Override
    public Optional<Definition> definition(String id) {
        return Optional.ofNullable(definitions.get(normalizeAnalysisId(id)));
    }

    @Override
    public Set<String> baseSources(String analysisId) {
        return switch (normalizeAnalysisId(analysisId)) {
            case ID_POC_001, ID_POC_007, ID_POC_008 -> Set.of("payment", "refund", "branch");
            case ID_POC_002, ID_POC_004 -> Set.of("seat", "seat_session", "branch");
            case ID_POC_003, ID_POC_006 -> Set.of("pass", "product", "payment", "refund", "branch");
            case ID_POC_005 -> Set.of("room", "room_reservation", "branch");
            case ID_POC_009 -> Set.of("work_task", "branch", "employee");
            case ID_POC_010 -> Set.of("attendance_record", "attendance_break", "attendance_correction", "employee", "branch");
            default -> Set.of();
        };
    }

    @Override
    public void validate(QuerySpec query, UserContext user) {
        ContractRules.require(query != null && query.mode() == QuerySpec.Mode.REGISTERED_ANALYSIS, "Registered analysis mode required");
        ContractRules.require(supports(query.analysisId()), "Unsupported OpenStudy analysis: " + (query == null ? null : query.analysisId()));
    }

    @Override
    public ExecutionPlan plan(QuerySpec query, UserContext user, Instant asOf, Instant snapshotAt) {
        validate(query, user);
        String subject = user == null ? "anonymous" : user.subjectId();
        Map<String, Object> scope = (user == null || user.policyBinding() == null) ? Map.of() : user.policyBinding().scope();
        return new ExecutionPlan(
                ExecutionPlan.VERSION,
                registry.version(),
                "openstudy-policy-v1",
                subject,
                scope,
                asOf != null ? asOf : clock.asOf(),
                snapshotAt != null ? snapshotAt : clock.asOf(),
                query,
                List.of(),
                Map.of(),
                List.of("id"),
                5
        );
    }

    @Override
    public List<TypedDataset> executeAll(ExecutionPlan plan, UserContext user, Cancellation cancellation) {
        cancellation.check();
        Collection<Long> branches = extractBranchScope(user);
        PocExecutionResult res = executeCase(plan.query().analysisId(), branches, Long.parseLong(user.subjectId()));
        cancellation.check();
        return res.envelope().datasets();
    }

    public String normalizeAnalysisId(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        return aliasToAnalysisId.getOrDefault(trimmed, aliasToAnalysisId.getOrDefault(trimmed.toUpperCase(Locale.ROOT), trimmed));
    }

    public Optional<String> resolveFromQuestion(String question) {
        if (question == null || question.isBlank()) return Optional.empty();
        String q = question.trim();
        if (aliasToAnalysisId.containsKey(q)) {
            return Optional.of(aliasToAnalysisId.get(q));
        }
        if (q.contains("지점별 매출") || (q.contains("이번 달") && q.contains("매출"))) {
            return Optional.of(ID_POC_001);
        }
        if (q.contains("붐벼") || q.contains("혼잡") || q.contains("피크")) {
            return Optional.of(ID_POC_002);
        }
        if (q.contains("TOP 5") || q.contains("잘 팔린 이용권") || q.contains("인기 이용권")) {
            return Optional.of(ID_POC_003);
        }
        if (q.contains("빈자리") || q.contains("남은 좌석") || (q.contains("지금") && q.contains("좌석"))) {
            return Optional.of(ID_POC_004);
        }
        if (q.contains("스터디룸 예약") || (q.contains("이번 주") && q.contains("스터디룸"))) {
            return Optional.of(ID_POC_005);
        }
        if (q.contains("7일 이내") || q.contains("만료되는 이용권") || q.contains("만료 예정")) {
            return Optional.of(ID_POC_006);
        }
        if (q.contains("일별 매출") || q.contains("매출 추이")) {
            return Optional.of(ID_POC_007);
        }
        if ((q.contains("이용권") && q.contains("스터디룸") && q.contains("사물함")) || q.contains("어디서 매출이 가장")) {
            return Optional.of(ID_POC_008);
        }
        if (q.contains("급하게 처리해야 할 업무") || q.contains("긴급 업무") || (q.contains("급하게") && q.contains("업무"))) {
            return Optional.of(ID_POC_009);
        }
        if (q.contains("직원별 근무시간") || (q.contains("지난주") && q.contains("근무시간"))) {
            return Optional.of(ID_POC_010);
        }
        return Optional.empty();
    }

    /**
     * Whether the semantic catalog still offers this saved analysis (#168). A saved question whose analysis is gone is
     * shown with a reason instead of being run, so the failure never reaches a transaction.
     */
    public boolean offers(String analysisId) {
        return analysisId != null && REGISTERED.contains(normalizeAnalysisId(analysisId));
    }

    /** The registered analyses (ADR-M10-003 M10-D14). */
    public static final java.util.Set<String> REGISTERED = java.util.Set.of(ID_POC_001, ID_POC_002, ID_POC_003, ID_POC_004,
            ID_POC_005, ID_POC_006, ID_POC_007, ID_POC_008, ID_POC_009, ID_POC_010);

    public PocExecutionResult executeCase(String caseOrAnalysisId, Collection<Long> branchScope, long requester) {
        String analysisId = resolveFromQuestion(caseOrAnalysisId).orElseGet(() -> normalizeAnalysisId(caseOrAnalysisId));
        return switch (analysisId) {
            case ID_POC_001 -> executePoc001BranchSales(branchScope, requester);
            case ID_POC_002 -> executePoc002HourlyCongestion(singleBranch(branchScope));
            case ID_POC_003 -> executePoc003Top5Passes(branchScope, requester);
            case ID_POC_004 -> executePoc004RealtimeSeats(singleBranch(branchScope));
            case ID_POC_005 -> executePoc005WeeklyRoomReservations(branchScope);
            case ID_POC_006 -> executePoc006ExpiringPasses(branchScope);
            case ID_POC_007 -> executePoc007DailySalesTrend(branchScope, requester);
            case ID_POC_008 -> executePoc008SalesByItemType(branchScope, requester);
            case ID_POC_009 -> executePoc009UrgentTasks(branchScope);
            case ID_POC_010 -> executePoc010WeeklyAttendance(branchScope);
            default -> throw new EngineException(EngineErrorCode.IR_UNSUPPORTED, "Unsupported analysis ID: " + caseOrAnalysisId);
        };
    }

    /**
     * #125: an analysis run under a refinement's conditions. A null {@code period} keeps the analysis default and an empty
     * {@code products} is every product; analyses without that condition never receive one (AiReuse.EDITABLE).
     */
    public PocExecutionResult executeCase(String analysisId, Collection<Long> branchScope, long requester,
            OpenStudyClock.KstPeriod period, Set<Long> products) {
        if (period == null && products.isEmpty()) return executeCase(analysisId, branchScope, requester);
        return switch (analysisId) {
            case ID_POC_001 -> executePoc001BranchSales(branchScope, requester, period);
            case ID_POC_002 -> executePoc002HourlyCongestion(singleBranch(branchScope), period);
            case ID_POC_003 -> executePoc003Top5Passes(branchScope, requester, period, products);
            case ID_POC_005 -> executePoc005WeeklyRoomReservations(branchScope, period);
            case ID_POC_006 -> executePoc006ExpiringPasses(branchScope, period, products);
            case ID_POC_007 -> executePoc007DailySalesTrend(branchScope, requester, period);
            case ID_POC_008 -> executePoc008SalesByItemType(branchScope, requester, period);
            case ID_POC_010 -> executePoc010WeeklyAttendance(branchScope, period);
            default -> throw new IllegalArgumentException("Analysis " + analysisId + " takes no period or product condition");
        };
    }

    /** #125: the period an analysis uses when nobody changed it, or null for the analyses that look at now. */
    public OpenStudyClock.KstPeriod defaultPeriod(String analysisId) {
        return switch (analysisId) {
            case ID_POC_001 -> clock.thisMonth();
            case ID_POC_002 -> clock.previousTwoWeeks();
            case ID_POC_003, ID_POC_007, ID_POC_008 -> clock.lastMonth();
            case ID_POC_005 -> clock.thisWeek();
            case ID_POC_006 -> clock.next7Days();
            case ID_POC_010 -> clock.lastWeek();
            default -> null;
        };
    }

    /** #125: which of these product IDs exist. Products are shared by every branch, so existing means visible. */
    public Set<Long> existingProducts(Set<Long> ids) {
        if (ids.isEmpty()) return Set.of();
        return new LinkedHashSet<>(entityManager.createQuery("SELECT p.id FROM Product p WHERE p.id IN :ids", Long.class)
                .setParameter("ids", ids).getResultList());
    }

    /** An analysis always names its branches; an empty scope is a caller error, never "every branch". */
    static Collection<Long> requireBranches(Collection<Long> branchScope) {
        if (branchScope == null || branchScope.isEmpty()) {
            throw new IllegalArgumentException("An analysis needs its branches");
        }
        return branchScope;
    }

    /** A one-branch analysis needs exactly one branch; anything else is a caller error, never a default branch. */
    static long singleBranch(Collection<Long> branchScope) {
        if (branchScope == null || branchScope.size() != 1) {
            throw new IllegalArgumentException("A single-branch analysis needs exactly one branch");
        }
        return branchScope.iterator().next();
    }

    private String branchName(long branchId) {
        return branchNames.names(List.of(branchId)).getOrDefault(branchId, "지점 " + branchId);
    }

    /**
     * {@code POC-001}: "이번 달 지점별 매출을 비교해줘" ({@code 2026-11-01T00:00+09:00} ~ {@code 2026-11-03T00:00+09:00}).
     */
    public PocExecutionResult executePoc001BranchSales(Collection<Long> branchScope, long requester) {
        return executePoc001BranchSales(branchScope, requester, null);
    }

    /** #125: {@code override} is a refinement's period; null keeps the analysis default. */
    public PocExecutionResult executePoc001BranchSales(Collection<Long> branchScope, long requester, OpenStudyClock.KstPeriod override) {
        var period = override != null ? override : clock.thisMonth();
        var processed = salesService.aggregateByBranch(requester,
                branchScope, period.startInclusive(), period.endExclusive(), RefundBasis.REFUND_PROCESSED_AT);
        var original = salesService.aggregateByBranch(requester,
                branchScope, period.startInclusive(), period.endExclusive(), RefundBasis.ORIGINAL_PAYMENT_DATE);

        Instant now = clock.asOf();
        var def = definitions.get(ID_POC_001);

        List<Map<String, Object>> branchRows = new ArrayList<>();
        for (var r : processed.rows()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("branch_id", r.branchId());
            row.put("branch_name", r.branchName());
            row.put("approved_krw", r.approvedKrw());
            row.put("refund_krw", r.refundKrw());
            row.put("net_krw", r.netKrw());
            row.put("approved_count", r.approvedCount());
            row.put("refund_count", r.refundCount());
            branchRows.add(row);
        }

        List<Map<String, Object>> totalRows = List.of(new LinkedHashMap<>(Map.of(
                "approved_krw", processed.totalApprovedKrw(),
                "refund_krw", processed.totalRefundKrw(),
                "net_krw", processed.totalNetKrw(),
                "approved_count", processed.totalApprovedCount(),
                "refund_count", processed.totalRefundCount()
        )));

        List<Map<String, Object>> contrastRows = new ArrayList<>();
        for (var r : original.rows()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("branch_id", r.branchId());
            row.put("approved_krw", r.approvedKrw());
            row.put("refund_krw", r.refundKrw());
            row.put("net_krw", r.netKrw());
            contrastRows.add(row);
        }

        var dsBranch = new TypedDataset("branchSales", def.outputs().get("branchSales"), branchRows, branchRows.size(), 0, 10, now, now);
        var dsTotals = new TypedDataset("salesTotals", def.outputs().get("salesTotals"), totalRows, 1, 0, 1, now, now);
        var dsContrast = new TypedDataset("basisContrast", def.outputs().get("basisContrast"), contrastRows, contrastRows.size(), 0, 10, now, now);
        var datasets = List.of(dsBranch, dsTotals, dsContrast);

        var view = new ViewSpec(
                CATALOG_VERSION,
                List.of(
                        new ViewSpec.Component(
                                "kpi",
                                new ViewSpec.Ref("KPI", COMPONENT_VERSION),
                                "salesTotals",
                                Map.of("values", List.of("approved_krw", "refund_krw", "net_krw")),
                                Map.of("title", "이번 달 전체 순매출 요약", "currency", "KRW"),
                                List.of()
                        ),
                        new ViewSpec.Component(
                                "chart",
                                new ViewSpec.Ref("BAR_CHART", COMPONENT_VERSION),
                                "branchSales",
                                Map.of("category", "branch_name", "value", "net_krw"),
                                Map.of("title", "지점별 순매출 비교", "currency", "KRW"),
                                List.of()
                        ),
                        new ViewSpec.Component(
                                "table",
                                new ViewSpec.Ref("DATA_TABLE", COMPONENT_VERSION),
                                "branchSales",
                                Map.of("columns", List.of("branch_id", "branch_name", "approved_krw", "refund_krw", "net_krw", "approved_count", "refund_count")),
                                Map.of("title", "지점별 매출 상세"),
                                List.of()
                        )
                ),
                Layout.stack(List.of(Layout.leaf("kpi"), Layout.leaf("chart"), Layout.leaf("table")))
        );

        var envelope = buildEnvelope("POC-001", datasets, view, null);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("branch_rows", processed.rows());
        summary.put("original_payment_date_basis_contrast", original.rows());
        summary.put("totals", Map.of(
                "approved_krw", processed.totalApprovedKrw(),
                "refund_krw", processed.totalRefundKrw(),
                "net_krw", processed.totalNetKrw()
        ));

        return new PocExecutionResult(
                "POC-001",
                ID_POC_001,
                "이번 달 지점별 매출을 비교해줘",
                "CONFIRMED_ADR",
                List.of("KPI", "BAR_CHART", "DATA_TABLE"),
                envelope,
                summary
        );
    }

    /**
     * {@code POC-002}: "○○점은 언제 가장 붐벼?" ({@code 2026-10-19T00:00+09:00} ~ {@code 2026-11-02T00:00+09:00}, 2 weeks, sampled hours 9/12/15/18/21 KST).
     */
    public PocExecutionResult executePoc002HourlyCongestion(long branchId) {
        return executePoc002HourlyCongestion(branchId, null);
    }

    /** #125: {@code override} is a refinement's period; null keeps the analysis default. */
    public PocExecutionResult executePoc002HourlyCongestion(long branchId, OpenStudyClock.KstPeriod override) {
        var period = override != null ? override : clock.previousTwoWeeks();
        Instant now = clock.asOf();
        var def = definitions.get(ID_POC_002);

        Long availableSeatsCount = entityManager.createQuery(
                        "SELECT COUNT(s.id) FROM Seat s WHERE s.branchId = :branchId AND CAST(s.status AS string) = 'AVAILABLE'",
                        Long.class)
                .setParameter("branchId", branchId)
                .getSingleResult();
        long availableSeats = availableSeatsCount == null ? 0L : availableSeatsCount;

        List<Object[]> sessionTuples = entityManager.createQuery(
                        "SELECT ss.checkedInAt, ss.checkedOutAt FROM SeatSession ss "
                                + "WHERE ss.branchId = :branchId AND ss.checkedInAt < :endExclusive "
                                + "AND (ss.checkedOutAt IS NULL OR ss.checkedOutAt > :startInclusive)",
                        Object[].class)
                .setParameter("branchId", branchId)
                .setParameter("startInclusive", period.startInclusive())
                .setParameter("endExclusive", period.endExclusive())
                .getResultList();

        // Group sample instants by (weekday 1..7, hourKst)
        Map<String, long[]> grid = new LinkedHashMap<>();
        for (int weekday = 1; weekday <= 7; weekday++) {
            for (int hour : SAMPLED_HOURS_KST) {
                // [0] = occupiedSamples, [1] = weekdayOccurrences
                grid.put(weekday + ":" + hour, new long[2]);
            }
        }

        for (LocalDate d = period.startDateKst(); d.isBefore(period.endDateExclusiveKst()); d = d.plusDays(1)) {
            int weekday = d.getDayOfWeek().getValue();
            for (int hour : SAMPLED_HOURS_KST) {
                Instant sampleInstant = d.atTime(hour, 0).atZone(OpenStudyClock.KST).toInstant();
                long occupiedAtSample = 0L;
                for (Object[] tuple : sessionTuples) {
                    Instant checkedIn = (Instant) tuple[0];
                    Instant checkedOut = (Instant) tuple[1];
                    if (!checkedIn.isAfter(sampleInstant) && (checkedOut == null || checkedOut.isAfter(sampleInstant))) {
                        occupiedAtSample++;
                    }
                }
                long[] cell = grid.get(weekday + ":" + hour);
                cell[0] += occupiedAtSample;
                cell[1] += 1;
            }
        }

        List<HeatmapCell> cells = new ArrayList<>(grid.size());
        List<Map<String, Object>> heatmapRows = new ArrayList<>(grid.size());
        double maxPct = -1.0;

        for (int weekday = 1; weekday <= 7; weekday++) {
            for (int hour : SAMPLED_HOURS_KST) {
                long[] acc = grid.get(weekday + ":" + hour);
                long occupiedSamples = acc[0];
                long capacity = availableSeats * acc[1];
                double pct = capacity > 0 ? Math.round((occupiedSamples * 1000.0) / capacity) / 10.0 : 0.0;
                if (pct > maxPct) {
                    maxPct = pct;
                }
                var cell = new HeatmapCell(weekday, hour, occupiedSamples, capacity, pct);
                cells.add(cell);

                var row = new LinkedHashMap<String, Object>();
                row.put("weekday", weekday);
                row.put("hour_kst", hour);
                row.put("occupied_seat_samples", occupiedSamples);
                row.put("seat_sample_capacity", capacity);
                row.put("sampled_occupancy_pct", pct);
                heatmapRows.add(row);
            }
        }

        final double peakThreshold = maxPct;
        List<HeatmapCell> peakCells = cells.stream()
                .filter(c -> Double.compare(c.sampledOccupancyPct(), peakThreshold) == 0)
                .toList();

        List<Map<String, Object>> peakDatasetRows = new ArrayList<>(peakCells.size());
        for (var cell : peakCells) {
            var row = new LinkedHashMap<String, Object>();
            row.put("weekday", cell.weekday());
            row.put("hour_kst", cell.hourKst());
            row.put("occupied_seat_samples", cell.occupiedSeatSamples());
            row.put("seat_sample_capacity", cell.seatSampleCapacity());
            row.put("sampled_occupancy_pct", cell.sampledOccupancyPct());
            peakDatasetRows.add(row);
        }

        var dsHeatmap = new TypedDataset("heatmapRows", def.outputs().get("heatmapRows"), heatmapRows, heatmapRows.size(), 0, 50, now, now);
        var dsPeaks = new TypedDataset("peakCells", def.outputs().get("peakCells"), peakDatasetRows, peakDatasetRows.size(), 0, 10, now, now);
        var datasets = List.of(dsHeatmap, dsPeaks);

        var view = new ViewSpec(
                CATALOG_VERSION,
                List.of(
                        new ViewSpec.Component(
                                "heatmap",
                                new ViewSpec.Ref("HEATMAP", COMPONENT_VERSION),
                                "heatmapRows",
                                Map.of("category", "weekday", "series", "hour_kst", "value", "sampled_occupancy_pct"),
                                Map.of("title", branchName(branchId) + " 요일·시간대별 좌석 점유율 히트맵", "unit", "%"),
                                List.of()
                        )
                ),
                Layout.stack(List.of(Layout.leaf("heatmap")))
        );

        var notice = new OutcomeNotice(
                "PROVISIONAL_OCCUPANCY_DEFINITION",
                OutcomeNotice.Kind.CLARIFICATION,
                "가용 좌석 23석 및 2주 샘플 시간대(09/12/15/18/21시) 기준 임시 점유율입니다.",
                "점유율 분모 및 시간 가중치 정책 확정 전 참고 수치"
        );
        var envelope = buildEnvelope("POC-002", datasets, view, notice);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "PROVISIONAL_NOT_GOLDEN");
        summary.put("sampled_heatmap_rows", cells);
        summary.put("peak_cells", peakCells);

        return new PocExecutionResult(
                "POC-002",
                ID_POC_002,
                branchName(branchId) + "은 언제 가장 붐벼?",
                "POLICY_PENDING_OCCUPANCY_DENOMINATOR_AND_TIME_WEIGHTING",
                List.of("HEATMAP"),
                envelope,
                summary
        );
    }

    /**
     * {@code POC-003}: "지난달 가장 잘 팔린 이용권 TOP 5를 보여줘" ({@code 2026-10-01T00:00+09:00} ~ {@code 2026-11-01T00:00+09:00}).
     */
    public PocExecutionResult executePoc003Top5Passes(Collection<Long> branchScope, long requester) {
        return executePoc003Top5Passes(branchScope, requester, null, Set.of());
    }

    /** #125: {@code override} is a refinement's period; null keeps the analysis default. An empty {@code products} is every product. */
    public PocExecutionResult executePoc003Top5Passes(Collection<Long> branchScope, long requester, OpenStudyClock.KstPeriod override, Set<Long> products) {
        var period = override != null ? override : clock.lastMonth();
        Instant now = clock.asOf();
        var def = definitions.get(ID_POC_003);
        // GET /sales/passes/products with the same condition (#95, M10-D05): M9's ranking by net sold quantity.
        // #125: M9 filters one product at a time, so a product condition ranks each chosen product's own row.
        var ranked = products.isEmpty()
                ? salesService.topPassProducts(requester, branchScope, period.startInclusive(), period.endExclusive(), 5)
                : products.stream().flatMap(product -> salesService.topPassProducts(requester, branchScope,
                        period.startInclusive(), period.endExclusive(), 1, product).stream()).toList();
        var rows = ranked.stream()
                .map(item -> new TopPassProductRow(Long.parseLong(item.productId()), item.productName(), item.soldQuantity(),
                        item.refundedQuantity(), item.netSoldQuantity()));
        List<TopPassProductRow> top5 = (products.isEmpty() ? rows : rows.sorted(Comparator
                .comparingLong(TopPassProductRow::netUnits).reversed().thenComparingLong(TopPassProductRow::productId)))
                .limit(5).toList();

        List<Map<String, Object>> datasetRows = new ArrayList<>(top5.size());
        for (var r : top5) {
            var row = new LinkedHashMap<String, Object>();
            row.put("product_id", r.productId());
            row.put("product", r.product());
            row.put("issued_units", r.issuedUnits());
            row.put("refunded_units", r.refundedUnits());
            row.put("net_units", r.netUnits());
            datasetRows.add(row);
        }

        var dsTop5 = new TypedDataset("topProducts", def.outputs().get("topProducts"), datasetRows, datasetRows.size(), 0, 5, now, now);
        var datasets = List.of(dsTop5);

        var view = new ViewSpec(
                CATALOG_VERSION,
                List.of(
                        new ViewSpec.Component(
                                "rankedBar",
                                new ViewSpec.Ref("RANKED_BAR_CHART", COMPONENT_VERSION),
                                "topProducts",
                                Map.of("category", "product", "value", "net_units"),
                                Map.of("title", "지난달 인기 이용권 TOP 5 (순판매 수량)", "unit", "개"),
                                List.of()
                        ),
                        new ViewSpec.Component(
                                "table",
                                new ViewSpec.Ref("DATA_TABLE", COMPONENT_VERSION),
                                "topProducts",
                                Map.of("columns", List.of("product_id", "product", "issued_units", "refunded_units", "net_units")),
                                Map.of("title", "이용권 상품별 판매·환불 상세"),
                                List.of()
                        )
                ),
                Layout.stack(List.of(Layout.leaf("rankedBar"), Layout.leaf("table")))
        );

        var envelope = buildEnvelope("POC-003", datasets, view, null);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("top5", top5);
        summary.put("ranking", "M9 /sales/passes/products: netSoldQuantity DESC, competition rank");
        summary.put("only_purpose", "PASS");

        return new PocExecutionResult(
                "POC-003",
                ID_POC_003,
                "지난달 가장 잘 팔린 이용권 TOP 5를 보여줘",
                "CONFIRMED_ADR",
                List.of("RANKED_BAR_CHART", "DATA_TABLE"),
                envelope,
                summary
        );
    }

    /**
     * {@code POC-004}: "지금 ○○점에 빈자리가 얼마나 있어?" ({@code asOf = 2026-11-02T14:00:00+09:00}).
     */
    public PocExecutionResult executePoc004RealtimeSeats(long branchId) {
        Instant now = clock.asOf();
        var def = definitions.get(ID_POC_004);

        List<Object[]> seatTuples = entityManager.createQuery(
                        "SELECT s.id, s.seatNo, s.status FROM Seat s WHERE s.branchId = :branchId ORDER BY s.seatNo ASC",
                        Object[].class)
                .setParameter("branchId", branchId)
                .getResultList();

        List<Long> occupiedSeatIds = entityManager.createQuery(
                        "SELECT ss.seatId FROM SeatSession ss WHERE ss.branchId = :branchId "
                                + "AND ss.checkedInAt <= :asOf AND (ss.checkedOutAt IS NULL OR ss.checkedOutAt > :asOf)",
                        Long.class)
                .setParameter("branchId", branchId)
                .setParameter("asOf", now)
                .getResultList();
        Set<Long> occupiedSet = new LinkedHashSet<>(occupiedSeatIds);

        long total = seatTuples.size();
        long available = 0L;
        long blocked = 0L;
        long occupied = 0L;

        List<Map<String, Object>> seatMapRows = new ArrayList<>(seatTuples.size());
        for (Object[] tuple : seatTuples) {
            long seatId = ((Number) tuple[0]).longValue();
            String seatNo = String.valueOf(tuple[1]);
            String status = String.valueOf(tuple[2]);
            boolean isOccupied = occupiedSet.contains(seatId);
            String occupancyState;
            if ("BLOCKED".equals(status)) {
                blocked++;
                occupancyState = "BLOCKED";
            } else {
                available++;
                if (isOccupied) {
                    occupied++;
                    occupancyState = "OCCUPIED";
                } else {
                    occupancyState = "FREE";
                }
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("seat_id", seatId);
            row.put("seat_no", seatNo);
            row.put("seat_status", status);
            row.put("occupancy_state", occupancyState);
            seatMapRows.add(row);
        }

        long free = available - occupied;
        double occupiedPct = available > 0 ? Math.round((occupied * 1000.0) / available) / 10.0 : 0.0;
        var summaryObj = new RealtimeSeatSummary(branchId, total, available, occupied, free, blocked, occupiedPct);

        var summaryRow = new LinkedHashMap<String, Object>();
        summaryRow.put("branch_id", branchId);
        summaryRow.put("total", total);
        summaryRow.put("available", available);
        summaryRow.put("occupied", occupied);
        summaryRow.put("free", free);
        summaryRow.put("blocked", blocked);
        summaryRow.put("occupied_pct", occupiedPct);

        var dsSummary = new TypedDataset("seatSummary", def.outputs().get("seatSummary"), List.of(summaryRow), 1, 0, 1, now, now);
        var dsMap = new TypedDataset("seatMap", def.outputs().get("seatMap"), seatMapRows, seatMapRows.size(), 0, 30, now, now);
        var datasets = List.of(dsSummary, dsMap);

        var view = new ViewSpec(
                CATALOG_VERSION,
                List.of(
                        new ViewSpec.Component(
                                "kpi",
                                new ViewSpec.Ref("KPI", COMPONENT_VERSION),
                                "seatSummary",
                                Map.of("values", List.of("free", "occupied", "available", "blocked", "occupied_pct")),
                                Map.of("title", branchName(branchId) + " 실시간 좌석 요약", "unit", "석"),
                                List.of()
                        ),
                        new ViewSpec.Component(
                                "seatMap",
                                new ViewSpec.Ref("SEAT_MAP", COMPONENT_VERSION),
                                "seatMap",
                                Map.of("columns", List.of("seat_id", "seat_no", "seat_status", "occupancy_state")),
                                Map.of("title", branchName(branchId) + " 좌석 배치 현황"),
                                List.of()
                        )
                ),
                Layout.stack(List.of(Layout.leaf("kpi"), Layout.leaf("seatMap")))
        );

        var envelope = buildEnvelope("POC-004", datasets, view, null);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("branch_id", branchId);
        summary.put("total", total);
        summary.put("available", available);
        summary.put("occupied", occupied);
        summary.put("free", free);
        summary.put("blocked", blocked);
        summary.put("occupied_pct", occupiedPct);
        summary.put("realtime_summary", summaryObj);

        return new PocExecutionResult(
                "POC-004",
                ID_POC_004,
                "지금 " + branchName(branchId) + "에 빈자리가 얼마나 있어?",
                "CONFIRMED_ADR",
                List.of("KPI", "SEAT_MAP"),
                envelope,
                summary
        );
    }

    /**
     * {@code POC-005}: "이번 주 스터디룸 예약 현황을 보여줘" ({@code 2026-11-02T00:00+09:00} ~ {@code 2026-11-09T00:00+09:00}).
     */
    public PocExecutionResult executePoc005WeeklyRoomReservations(Collection<Long> branchScope) {
        return executePoc005WeeklyRoomReservations(branchScope, null);
    }

    /** #125: {@code override} is a refinement's period; null keeps the analysis default. */
    public PocExecutionResult executePoc005WeeklyRoomReservations(Collection<Long> branchScope, OpenStudyClock.KstPeriod override) {
        var period = override != null ? override : clock.thisWeek();
        Instant now = clock.asOf();
        var def = definitions.get(ID_POC_005);
        Collection<Long> effectiveBranches = requireBranches(branchScope);

        List<Object[]> branchTuples = entityManager.createQuery(
                        "SELECT b.id, b.name FROM Branch b WHERE b.id IN :branchIds ORDER BY b.id ASC",
                        Object[].class)
                .setParameter("branchIds", effectiveBranches)
                .getResultList();

        Map<Long, String> branchNames = new LinkedHashMap<>();
        for (Object[] tuple : branchTuples) {
            branchNames.put(((Number) tuple[0]).longValue(), String.valueOf(tuple[1]));
        }

        List<Object[]> reservationTuples = entityManager.createQuery(
                        "SELECT rr.id, r.branchId, r.id, r.name, rr.startAt, rr.endAt, rr.status, rr.ratePerHourSnapshotKrw "
                                + "FROM RoomReservation rr, Room r WHERE r.id = rr.roomId AND r.branchId IN :branchIds "
                                + "AND rr.startAt >= :startInclusive AND rr.startAt < :endExclusive "
                                + "ORDER BY rr.startAt ASC, rr.id ASC",
                        Object[].class)
                .setParameter("branchIds", effectiveBranches)
                .setParameter("startInclusive", period.startInclusive())
                .setParameter("endExclusive", period.endExclusive())
                .getResultList();

        Map<Long, Map<String, Long>> byStatusPerBranch = new LinkedHashMap<>();
        // [0]=total, [1]=confirmed, [2]=completed, [3]=cancelled, [4]=billableKrw
        Map<Long, long[]> branchAcc = new LinkedHashMap<>();
        for (Long bId : branchNames.keySet()) {
            byStatusPerBranch.put(bId, new LinkedHashMap<>());
            branchAcc.put(bId, new long[5]);
        }

        List<Map<String, Object>> reservationRows = new ArrayList<>(reservationTuples.size());
        long allBookings = 0L;
        long totalCancelled = 0L;
        long totalBillableKrw = 0L;

        for (Object[] tuple : reservationTuples) {
            long reservationId = ((Number) tuple[0]).longValue();
            long branchId = ((Number) tuple[1]).longValue();
            long roomId = ((Number) tuple[2]).longValue();
            String roomName = String.valueOf(tuple[3]);
            Instant startAt = (Instant) tuple[4];
            Instant endAt = (Instant) tuple[5];
            String status = String.valueOf(tuple[6]);
            long ratePerHour = ((Number) tuple[7]).longValue();

            long hours = Duration.between(startAt, endAt).toMinutes() / 60;
            boolean billable = !"CANCELLED".equals(status);
            long billableAmount = billable ? hours * ratePerHour : 0L;

            long[] acc = branchAcc.get(branchId);
            if (acc != null) {
                acc[0] += 1;
                if ("CONFIRMED".equals(status)) acc[1] += 1;
                else if ("COMPLETED".equals(status)) acc[2] += 1;
                else if ("CANCELLED".equals(status)) acc[3] += 1;
                acc[4] += billableAmount;
            }
            Map<String, Long> statusMap = byStatusPerBranch.get(branchId);
            if (statusMap != null) {
                statusMap.merge(status, 1L, Long::sum);
            }

            allBookings++;
            if ("CANCELLED".equals(status)) {
                totalCancelled++;
            }
            totalBillableKrw += billableAmount;

            var row = new LinkedHashMap<String, Object>();
            row.put("reservation_id", reservationId);
            row.put("branch_id", branchId);
            row.put("branch_name", branchNames.getOrDefault(branchId, ""));
            row.put("room_id", roomId);
            row.put("room_name", roomName);
            row.put("start_at", startAt);
            row.put("end_at", endAt);
            row.put("status", status);
            row.put("billable_amount_krw", billableAmount);
            reservationRows.add(row);
        }

        List<Map<String, Object>> branchSummaryRows = new ArrayList<>(branchNames.size());
        Map<String, BranchRoomReservationSummary> branchSummariesMap = new LinkedHashMap<>();
        for (var entry : branchNames.entrySet()) {
            long bId = entry.getKey();
            long[] acc = branchAcc.get(bId);
            var row = new LinkedHashMap<String, Object>();
            row.put("branch_id", bId);
            row.put("branch_name", entry.getValue());
            row.put("total", acc[0]);
            row.put("confirmed_count", acc[1]);
            row.put("completed_count", acc[2]);
            row.put("cancelled_count", acc[3]);
            row.put("billable_booking_amount_krw", acc[4]);
            branchSummaryRows.add(row);

            branchSummariesMap.put(Long.toString(bId), new BranchRoomReservationSummary(
                    bId,
                    entry.getValue(),
                    acc[0],
                    Map.copyOf(byStatusPerBranch.get(bId)),
                    acc[0] - acc[3],
                    acc[3],
                    acc[4]
            ));
        }

        var totalsRow = new LinkedHashMap<String, Object>();
        totalsRow.put("all_bookings", allBookings);
        totalsRow.put("cancelled", totalCancelled);
        totalsRow.put("active_bookings", allBookings - totalCancelled);
        totalsRow.put("billable_booking_amount_krw", totalBillableKrw);

        var dsReservations = new TypedDataset("reservationRows", def.outputs().get("reservationRows"), reservationRows, reservationRows.size(), 0, 100, now, now);
        var dsBranches = new TypedDataset("branchSummaries", def.outputs().get("branchSummaries"), branchSummaryRows, branchSummaryRows.size(), 0, 10, now, now);
        var dsTotals = new TypedDataset("reservationTotals", def.outputs().get("reservationTotals"), List.of(totalsRow), 1, 0, 1, now, now);
        var datasets = List.of(dsReservations, dsBranches, dsTotals);

        var view = new ViewSpec(
                CATALOG_VERSION,
                List.of(
                        new ViewSpec.Component(
                                "calendar",
                                new ViewSpec.Ref("WEEK_CALENDAR", COMPONENT_VERSION),
                                "reservationRows",
                                Map.of("columns", List.of("reservation_id", "branch_name", "room_name", "start_at", "end_at", "status", "billable_amount_krw")),
                                Map.of("title", "이번 주 스터디룸 주간 예약 캘린더"),
                                List.of()
                        ),
                        new ViewSpec.Component(
                                "table",
                                new ViewSpec.Ref("DATA_TABLE", COMPONENT_VERSION),
                                "branchSummaries",
                                Map.of("columns", List.of("branch_id", "branch_name", "total", "confirmed_count", "completed_count", "cancelled_count", "billable_booking_amount_krw")),
                                Map.of("title", "지점별 스터디룸 예약 요약"),
                                List.of()
                        )
                ),
                Layout.stack(List.of(Layout.leaf("calendar"), Layout.leaf("table")))
        );

        var envelope = buildEnvelope("POC-005", datasets, view, null);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("branch_summaries", branchSummariesMap);
        summary.put("all_bookings", allBookings);
        summary.put("cancelled", totalCancelled);
        summary.put("active_bookings", allBookings - totalCancelled);
        summary.put("total_billable_booking_amount_krw", totalBillableKrw);

        return new PocExecutionResult(
                "POC-005",
                ID_POC_005,
                "이번 주 스터디룸 예약 현황을 보여줘",
                "CONFIRMED_ADR",
                List.of("WEEK_CALENDAR", "DATA_TABLE"),
                envelope,
                summary
        );
    }

    /**
     * {@code POC-006}: "앞으로 7일 이내에 만료되는 이용권을 보여줘" ({@code [2026-11-02T14:00:00+09:00, 2026-11-09T14:00:00+09:00)}).
     * Never exposes customer PII (`customer_id`, `customer.name`, `customer.phone`, `customer.email`).
     */
    public PocExecutionResult executePoc006ExpiringPasses(Collection<Long> branchScope) {
        return executePoc006ExpiringPasses(branchScope, null, Set.of());
    }

    /** #125: {@code override} is a refinement's period; null keeps the analysis default. An empty {@code products} is every product. */
    public PocExecutionResult executePoc006ExpiringPasses(Collection<Long> branchScope, OpenStudyClock.KstPeriod override, Set<Long> products) {
        var period = override != null ? override : clock.next7Days();
        Instant now = clock.asOf();
        var def = definitions.get(ID_POC_006);
        Collection<Long> effectiveBranches = requireBranches(branchScope);

        List<Object[]> branchTuples = entityManager.createQuery(
                        "SELECT b.id, b.name FROM Branch b WHERE b.id IN :branchIds ORDER BY b.id ASC",
                        Object[].class)
                .setParameter("branchIds", effectiveBranches)
                .getResultList();

        Map<Long, String> branchNames = new LinkedHashMap<>();
        Map<Long, Long> byBranchCount = new LinkedHashMap<>();
        for (Object[] tuple : branchTuples) {
            long bId = ((Number) tuple[0]).longValue();
            branchNames.put(bId, String.valueOf(tuple[1]));
            byBranchCount.put(bId, 0L);
        }

        var passQuery = entityManager.createQuery(
                        "SELECT pa.id, pa.purchaseBranchId, pa.productId, pa.snapshotProductName, pa.kind, "
                                + "pa.remainingMinutes, pa.expiresAt, pa.status "
                                + "FROM Pass pa WHERE pa.purchaseBranchId IN :branchIds "
                                + "AND CAST(pa.status AS string) = 'ACTIVE' "
                                + "AND pa.expiresAt >= :startInclusive AND pa.expiresAt < :endExclusive "
                                + (products.isEmpty() ? "" : "AND pa.productId IN :productIds ")
                                + "ORDER BY pa.expiresAt ASC, pa.id ASC",
                        Object[].class)
                .setParameter("branchIds", effectiveBranches)
                .setParameter("startInclusive", period.startInclusive())
                .setParameter("endExclusive", period.endExclusive());
        if (!products.isEmpty()) passQuery.setParameter("productIds", products);
        List<Object[]> passTuples = passQuery.getResultList();

        List<ExpiringPassItem> items = new ArrayList<>(passTuples.size());
        List<Long> passIds = new ArrayList<>(passTuples.size());
        List<Map<String, Object>> passRows = new ArrayList<>(passTuples.size());

        for (Object[] tuple : passTuples) {
            long passId = ((Number) tuple[0]).longValue();
            long branchId = ((Number) tuple[1]).longValue();
            long productId = ((Number) tuple[2]).longValue();
            String productName = String.valueOf(tuple[3]);
            String passType = String.valueOf(tuple[4]);
            Long remainingMinutes = tuple[5] == null ? null : ((Number) tuple[5]).longValue();
            Instant expiresAt = (Instant) tuple[6];
            String status = String.valueOf(tuple[7]);
            String branchName = branchNames.getOrDefault(branchId, "");

            byBranchCount.computeIfPresent(branchId, (k, v) -> v + 1L);
            passIds.add(passId);
            items.add(new ExpiringPassItem(passId, branchId, branchName, productId, productName, passType, remainingMinutes, expiresAt, status));

            var row = new LinkedHashMap<String, Object>();
            row.put("pass_id", passId);
            row.put("branch_id", branchId);
            row.put("branch_name", branchName);
            row.put("product_id", productId);
            row.put("product_name", productName);
            row.put("pass_type", passType);
            row.put("remaining_minutes", remainingMinutes);
            row.put("expires_at", expiresAt);
            row.put("status", status);
            passRows.add(row);
        }

        List<Map<String, Object>> branchRows = new ArrayList<>(branchNames.size());
        Map<String, Long> byBranchStringKey = new LinkedHashMap<>();
        for (var entry : branchNames.entrySet()) {
            long bId = entry.getKey();
            long cnt = byBranchCount.getOrDefault(bId, 0L);
            byBranchStringKey.put(Long.toString(bId), cnt);
            var row = new LinkedHashMap<String, Object>();
            row.put("branch_id", bId);
            row.put("branch_name", entry.getValue());
            row.put("expiring_count", cnt);
            branchRows.add(row);
        }

        var totalsRow = new LinkedHashMap<String, Object>(Map.of("count", (long) items.size()));

        var dsPasses = new TypedDataset("expiringPasses", def.outputs().get("expiringPasses"), passRows, passRows.size(), 0, 100, now, now);
        var dsByBranch = new TypedDataset("expiringByBranch", def.outputs().get("expiringByBranch"), branchRows, branchRows.size(), 0, 10, now, now);
        var dsTotals = new TypedDataset("expiringTotals", def.outputs().get("expiringTotals"), List.of(totalsRow), 1, 0, 1, now, now);
        var datasets = List.of(dsPasses, dsByBranch, dsTotals);

        var view = new ViewSpec(
                CATALOG_VERSION,
                List.of(
                        new ViewSpec.Component(
                                "kpi",
                                new ViewSpec.Ref("KPI", COMPONENT_VERSION),
                                "expiringTotals",
                                Map.of("values", List.of("count")),
                                Map.of("title", "7일 이내 만료 예정 이용권 수", "unit", "건"),
                                List.of()
                        ),
                        new ViewSpec.Component(
                                "table",
                                new ViewSpec.Ref("DATA_TABLE", COMPONENT_VERSION),
                                "expiringPasses",
                                Map.of("columns", List.of("pass_id", "branch_name", "product_name", "pass_type", "remaining_minutes", "expires_at", "status")),
                                Map.of("title", "만료 예정 이용권 목록 (개인정보 비노출)"),
                                List.of()
                        )
                ),
                Layout.stack(List.of(Layout.leaf("kpi"), Layout.leaf("table")))
        );

        var envelope = buildEnvelope("POC-006", datasets, view, null);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", (long) items.size());
        summary.put("by_branch", byBranchStringKey);
        summary.put("pass_ids", passIds);
        summary.put("status", "ACTIVE");
        summary.put("boundary", "[as_of,as_of+7days)");

        return new PocExecutionResult(
                "POC-006",
                ID_POC_006,
                "앞으로 7일 이내에 만료되는 이용권을 보여줘",
                "PROVISIONAL_END_EXCLUSIVE_BOUNDARY_REQUIRES_CONFIRMATION",
                List.of("KPI", "DATA_TABLE"),
                envelope,
                summary
        );
    }

    /**
     * {@code POC-007}: "지난달 일별 매출 추이를 그래프로 보여줘" ({@code 2026-10-01} ~ {@code 2026-10-31}, 31 days zero-filled).
     */
    public PocExecutionResult executePoc007DailySalesTrend(Collection<Long> branchScope, long requester) {
        return executePoc007DailySalesTrend(branchScope, requester, null);
    }

    /** #125: {@code override} is a refinement's period; null keeps the analysis default. */
    public PocExecutionResult executePoc007DailySalesTrend(Collection<Long> branchScope, long requester, OpenStudyClock.KstPeriod override) {
        var period = override != null ? override : clock.lastMonth();
        Instant now = clock.asOf();
        var def = definitions.get(ID_POC_007);

        var trend = salesService.aggregateDailyTrend(requester,
                branchScope,
                period.startDateKst(),
                period.endDateExclusiveKst(),
                RefundBasis.REFUND_PROCESSED_AT
        );

        List<Map<String, Object>> dayRows = new ArrayList<>(trend.days().size());
        for (var d : trend.days()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("date_kst", d.dateKst());
            row.put("approved_krw", d.approvedKrw());
            row.put("refund_krw", d.refundKrw());
            row.put("net_krw", d.netKrw());
            row.put("approved_count", d.approvedCount());
            row.put("refund_count", d.refundCount());
            dayRows.add(row);
        }

        var totalsRow = new LinkedHashMap<String, Object>();
        totalsRow.put("days_count", (long) trend.daysCount());
        totalsRow.put("approved_krw", trend.totalApprovedKrw());
        totalsRow.put("refund_krw", trend.totalRefundKrw());
        totalsRow.put("total_net_krw", trend.totalNetKrw());

        var dsDaily = new TypedDataset("dailySales", def.outputs().get("dailySales"), dayRows, dayRows.size(), 0, 31, now, now);
        var dsTotals = new TypedDataset("dailySalesTotals", def.outputs().get("dailySalesTotals"), List.of(totalsRow), 1, 0, 1, now, now);
        var datasets = List.of(dsDaily, dsTotals);

        var view = new ViewSpec(
                CATALOG_VERSION,
                List.of(
                        new ViewSpec.Component(
                                "lineChart",
                                new ViewSpec.Ref("LINE_CHART", COMPONENT_VERSION),
                                "dailySales",
                                Map.of("category", "date_kst", "value", "net_krw"),
                                Map.of("title", "지난달 일별 순매출 추이 (0원 일자 포함)", "currency", "KRW"),
                                List.of()
                        ),
                        new ViewSpec.Component(
                                "table",
                                new ViewSpec.Ref("DATA_TABLE", COMPONENT_VERSION),
                                "dailySales",
                                Map.of("columns", List.of("date_kst", "approved_krw", "refund_krw", "net_krw", "approved_count", "refund_count")),
                                Map.of("title", "지난달 일별 매출 상세"),
                                List.of()
                        )
                ),
                Layout.stack(List.of(Layout.leaf("lineChart"), Layout.leaf("table")))
        );

        var envelope = buildEnvelope("POC-007", datasets, view, null);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("days", trend.days());
        summary.put("zero_filled", trend.zeroFilled());
        summary.put("days_count", trend.daysCount());
        summary.put("total_approved_krw", trend.totalApprovedKrw());
        summary.put("total_refund_krw", trend.totalRefundKrw());
        summary.put("total_net_krw", trend.totalNetKrw());

        return new PocExecutionResult(
                "POC-007",
                ID_POC_007,
                "지난달 일별 매출 추이를 그래프로 보여줘",
                "CONFIRMED_ADR",
                List.of("LINE_CHART", "DATA_TABLE"),
                envelope,
                summary
        );
    }

    /**
     * {@code POC-008}: "지난달 이용권, 스터디룸, 사물함 중 어디서 매출이 가장 많이 나왔어?" ({@code 2026-10-01} ~ {@code 2026-11-01} KST).
     */
    public PocExecutionResult executePoc008SalesByItemType(Collection<Long> branchScope, long requester) {
        return executePoc008SalesByItemType(branchScope, requester, null);
    }

    /** #125: {@code override} is a refinement's period; null keeps the analysis default. */
    public PocExecutionResult executePoc008SalesByItemType(Collection<Long> branchScope, long requester, OpenStudyClock.KstPeriod override) {
        var period = override != null ? override : clock.lastMonth();
        Instant now = clock.asOf();
        var def = definitions.get(ID_POC_008);

        var byPurpose = salesService.aggregateByPurpose(requester,
                branchScope,
                period.startInclusive(),
                period.endExclusive(),
                RefundBasis.REFUND_PROCESSED_AT
        );

        List<Map<String, Object>> purposeRows = new ArrayList<>(byPurpose.purposeRows().size());
        for (var r : byPurpose.purposeRows()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("purpose", r.purpose());
            row.put("approved_krw", r.approvedKrw());
            row.put("refund_krw", r.refundKrw());
            row.put("net_krw", r.netKrw());
            row.put("approved_count", r.approvedCount());
            row.put("refund_count", r.refundCount());
            row.put("share_pct", r.sharePct());
            purposeRows.add(row);
        }

        long winnerNetKrw = byPurpose.purposeRows().isEmpty() ? 0L : byPurpose.purposeRows().getFirst().netKrw();
        var winnerRow = new LinkedHashMap<String, Object>();
        winnerRow.put("winner_purpose", byPurpose.winnerPurpose());
        winnerRow.put("ranking_metric", byPurpose.rankingMetric());
        winnerRow.put("winner_net_krw", winnerNetKrw);
        winnerRow.put("total_net_krw", byPurpose.totalNetKrw());

        var dsPurpose = new TypedDataset("purposeSales", def.outputs().get("purposeSales"), purposeRows, purposeRows.size(), 0, 10, now, now);
        var dsWinner = new TypedDataset("purposeWinner", def.outputs().get("purposeWinner"), List.of(winnerRow), 1, 0, 1, now, now);
        var datasets = List.of(dsPurpose, dsWinner);

        var view = new ViewSpec(
                CATALOG_VERSION,
                List.of(
                        new ViewSpec.Component(
                                "kpi",
                                new ViewSpec.Ref("KPI", COMPONENT_VERSION),
                                "purposeWinner",
                                Map.of("values", List.of("winner_purpose", "winner_net_krw", "total_net_krw")),
                                Map.of("title", "지난달 최고 매출 부문 요약", "currency", "KRW"),
                                List.of()
                        ),
                        new ViewSpec.Component(
                                "barChart",
                                new ViewSpec.Ref("BAR_CHART", COMPONENT_VERSION),
                                "purposeSales",
                                Map.of("category", "purpose", "value", "net_krw"),
                                Map.of("title", "상품 구분별 순매출 비교 (PASS / ROOM / LOCKER)", "currency", "KRW"),
                                List.of()
                        )
                ),
                Layout.stack(List.of(Layout.leaf("kpi"), Layout.leaf("barChart")))
        );

        var envelope = buildEnvelope("POC-008", datasets, view, null);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("purpose_rows", byPurpose.purposeRows());
        summary.put("winner_purpose", byPurpose.winnerPurpose());
        summary.put("ranking_metric", byPurpose.rankingMetric());
        summary.put("total_net_krw", byPurpose.totalNetKrw());

        return new PocExecutionResult(
                "POC-008",
                ID_POC_008,
                "지난달 이용권, 스터디룸, 사물함 중 어디서 매출이 가장 많이 나왔어?",
                "CONFIRMED_ADR",
                List.of("BAR_CHART", "KPI"),
                envelope,
                summary
        );
    }

    /**
     * {@code POC-009}: "지금 급하게 처리해야 할 업무가 뭐야?" ({@code asOf = 2026-11-02T14:00:00+09:00}).
     * Never selects or exposes {@code work_task.body}.
     */
    public PocExecutionResult executePoc009UrgentTasks(Collection<Long> branchScope) {
        var today = clock.today();
        Instant now = clock.asOf();
        var def = definitions.get(ID_POC_009);
        Collection<Long> effectiveBranches = requireBranches(branchScope);

        List<Object[]> branchTuples = entityManager.createQuery(
                        "SELECT b.id, b.name FROM Branch b WHERE b.id IN :branchIds ORDER BY b.id ASC",
                        Object[].class)
                .setParameter("branchIds", effectiveBranches)
                .getResultList();
        Map<Long, String> branchNames = new LinkedHashMap<>();
        for (Object[] tuple : branchTuples) {
            branchNames.put(((Number) tuple[0]).longValue(), String.valueOf(tuple[1]));
        }

        List<Object[]> taskTuples = entityManager.createQuery(
                        "SELECT wt.id, wt.title, wt.branchId, wt.priority, wt.status, wt.dueAt, wt.assigneeEmployeeId "
                                + "FROM WorkTask wt WHERE wt.branchId IN :branchIds "
                                + "AND CAST(wt.status AS string) IN ('TODO', 'IN_PROGRESS') "
                                + "AND CAST(wt.priority AS string) IN ('URGENT', 'HIGH') "
                                + "AND wt.dueAt IS NOT NULL AND wt.dueAt < :endOfTodayKst "
                                + "ORDER BY wt.id ASC",
                        Object[].class)
                .setParameter("branchIds", effectiveBranches)
                .setParameter("endOfTodayKst", today.endExclusive())
                .getResultList();

        List<UrgentTaskCandidate> candidates = new ArrayList<>(taskTuples.size());
        List<Map<String, Object>> taskRows = new ArrayList<>(taskTuples.size());

        for (Object[] tuple : taskTuples) {
            long id = ((Number) tuple[0]).longValue();
            String title = String.valueOf(tuple[1]);
            long branchId = ((Number) tuple[2]).longValue();
            String priority = String.valueOf(tuple[3]);
            String status = String.valueOf(tuple[4]);
            Instant dueAt = (Instant) tuple[5];
            Long assigneeId = tuple[6] == null ? null : ((Number) tuple[6]).longValue();
            String branchName = branchNames.getOrDefault(branchId, "");

            candidates.add(new UrgentTaskCandidate(id, title, branchId, branchName, priority, status, dueAt, assigneeId));

            var row = new LinkedHashMap<String, Object>();
            row.put("id", id);
            row.put("title", title);
            row.put("branch_id", branchId);
            row.put("branch_name", branchName);
            row.put("priority", priority);
            row.put("status", status);
            row.put("due_at", dueAt);
            row.put("assignee_employee_id", assigneeId);
            taskRows.add(row);
        }

        var metaRow = new LinkedHashMap<String, Object>();
        metaRow.put("candidate_count", (long) candidates.size());
        metaRow.put("status", "PROVISIONAL_NOT_GOLDEN");

        var dsTasks = new TypedDataset("urgentTasks", def.outputs().get("urgentTasks"), taskRows, taskRows.size(), 0, 20, now, now);
        var dsMeta = new TypedDataset("taskPolicyMeta", def.outputs().get("taskPolicyMeta"), List.of(metaRow), 1, 0, 1, now, now);
        var datasets = List.of(dsTasks, dsMeta);

        var view = new ViewSpec(
                CATALOG_VERSION,
                List.of(
                        new ViewSpec.Component(
                                "taskList",
                                new ViewSpec.Ref("TASK_LIST", COMPONENT_VERSION),
                                "urgentTasks",
                                Map.of("columns", List.of("id", "title", "branch_id", "branch_name", "priority", "status", "due_at", "assignee_employee_id")),
                                Map.of("title", "긴급·높음 우선순위 당일 및 지연 미완료 업무 후보"),
                                List.of()
                        )
                ),
                Layout.stack(List.of(Layout.leaf("taskList")))
        );

        var notice = new OutcomeNotice(
                "POLICY_PENDING_URGENT_DEFINITION",
                OutcomeNotice.Kind.CLARIFICATION,
                "긴급 업무 정렬 및 마감 기한 컷오프 정책 확정 전 후보 목록입니다.",
                "due_at cutoff, priority weighting, assignee vs branch scope, order 정책 확정 필요"
        );
        var envelope = buildEnvelope("POC-009", datasets, view, notice);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "PROVISIONAL_NOT_GOLDEN");
        summary.put("illustrative_due_today_or_earlier_and_high_or_urgent", candidates);
        summary.put("candidate_count", candidates.size());
        summary.put("needs_decision", List.of("due_at cutoff", "priority weighting", "assignee vs branch scope", "order"));

        return new PocExecutionResult(
                "POC-009",
                ID_POC_009,
                "지금 급하게 처리해야 할 업무가 뭐야?",
                "POLICY_PENDING_URGENT_DEFINITION",
                List.of("TASK_LIST"),
                envelope,
                summary
        );
    }

    /**
     * {@code POC-010}: "지난주 직원별 근무시간을 비교해줘" ({@code [2026-10-26T00:00:00+09:00, 2026-11-02T00:00:00+09:00)}).
     */
    public PocExecutionResult executePoc010WeeklyAttendance(Collection<Long> branchScope) {
        return executePoc010WeeklyAttendance(branchScope, null);
    }

    /** #125: {@code override} is a refinement's period; null keeps the analysis default. */
    public PocExecutionResult executePoc010WeeklyAttendance(Collection<Long> branchScope, OpenStudyClock.KstPeriod override) {
        var period = override != null ? override : clock.lastWeek();
        Instant now = clock.asOf();
        var def = definitions.get(ID_POC_010);
        Collection<Long> effectiveBranches = requireBranches(branchScope);

        List<Object[]> branchTuples = entityManager.createQuery(
                        "SELECT b.id, b.name FROM Branch b WHERE b.id IN :branchIds ORDER BY b.id ASC",
                        Object[].class)
                .setParameter("branchIds", effectiveBranches)
                .getResultList();
        Map<Long, String> branchNames = new LinkedHashMap<>();
        for (Object[] tuple : branchTuples) {
            branchNames.put(((Number) tuple[0]).longValue(), String.valueOf(tuple[1]));
        }

        List<Object[]> empTuples = entityManager.createQuery(
                        "SELECT e.id, e.name FROM Employee e ORDER BY e.id ASC",
                        Object[].class)
                .getResultList();
        Map<Long, String> employeeNames = new LinkedHashMap<>();
        for (Object[] tuple : empTuples) {
            employeeNames.put(((Number) tuple[0]).longValue(), String.valueOf(tuple[1]));
        }

        List<Object[]> recordTuples = entityManager.createQuery(
                        "SELECT ar.id, ar.employeeId, ar.branchId, ar.clockInAt, ar.clockOutAt "
                                + "FROM AttendanceRecord ar WHERE ar.branchId IN :branchIds "
                                + "AND ar.clockInAt >= :startInclusive AND ar.clockInAt < :endExclusive "
                                + "ORDER BY ar.employeeId ASC, ar.clockInAt ASC",
                        Object[].class)
                .setParameter("branchIds", effectiveBranches)
                .setParameter("startInclusive", period.startInclusive())
                .setParameter("endExclusive", period.endExclusive())
                .getResultList();

        List<Long> attendanceIds = recordTuples.stream().map(t -> ((Number) t[0]).longValue()).toList();
        // Approval rewrites the attendance and its breaks (#107), so the stored rows are the corrected ones and the
        // originals come back from the earliest approved correction's before_* values.
        Map<Long, List<Instant[]>> currentBreaksByAttendanceId = new LinkedHashMap<>();
        Map<Long, List<Instant[]>> originalBreaksByAttendanceId = new LinkedHashMap<>();
        Map<Long, Instant[]> originalClocks = new LinkedHashMap<>();

        if (!attendanceIds.isEmpty()) {
            List<Object[]> corrTuples = entityManager.createQuery(
                            "SELECT ac.attendanceRecordId, CAST(ac.correctionKind AS string), ac.breakId, "
                                    + "ac.beforeClockInAt, ac.beforeClockOutAt, ac.beforeBreakStartAt, ac.beforeBreakEndAt "
                                    + "FROM AttendanceCorrection ac WHERE ac.attendanceRecordId IN :attendanceIds "
                                    + "AND CAST(ac.status AS string) = 'APPROVED' ORDER BY ac.decidedAt ASC, ac.id ASC",
                            Object[].class)
                    .setParameter("attendanceIds", attendanceIds)
                    .getResultList();
            Map<Long, Instant[]> originalBreaks = new LinkedHashMap<>();
            for (Object[] tuple : corrTuples) {
                long attId = ((Number) tuple[0]).longValue();
                if ("CLOCK".equals(tuple[1])) {
                    originalClocks.putIfAbsent(attId, new Instant[]{(Instant) tuple[3], (Instant) tuple[4]});
                } else if (tuple[2] != null) {
                    originalBreaks.putIfAbsent(((Number) tuple[2]).longValue(), new Instant[]{(Instant) tuple[5], (Instant) tuple[6]});
                }
            }

            List<Object[]> breakTuples = entityManager.createQuery(
                            "SELECT ab.id, ab.attendanceId, ab.startedAt, ab.endedAt FROM AttendanceBreak ab "
                                    + "WHERE ab.attendanceId IN :attendanceIds AND ab.endedAt IS NOT NULL",
                            Object[].class)
                    .setParameter("attendanceIds", attendanceIds)
                    .getResultList();
            for (Object[] tuple : breakTuples) {
                long attId = ((Number) tuple[1]).longValue();
                Instant[] current = {(Instant) tuple[2], (Instant) tuple[3]};
                Instant[] original = originalBreaks.getOrDefault(((Number) tuple[0]).longValue(), current);
                currentBreaksByAttendanceId.computeIfAbsent(attId, k -> new ArrayList<>()).add(current);
                originalBreaksByAttendanceId.computeIfAbsent(attId, k -> new ArrayList<>()).add(new Instant[]{
                        original[0] != null ? original[0] : current[0], original[1] != null ? original[1] : current[1]});
            }
        }

        // [0]=branchId, [1]=shiftDays, [2]=rawWorkMinutes, [3]=correctedWorkMinutes
        Map<Long, long[]> empAcc = new LinkedHashMap<>();
        for (Object[] tuple : recordTuples) {
            long attId = ((Number) tuple[0]).longValue();
            long empId = ((Number) tuple[1]).longValue();
            long branchId = ((Number) tuple[2]).longValue();
            Instant clockIn = (Instant) tuple[3];
            Instant clockOut = (Instant) tuple[4];
            if (clockOut == null) continue;

            Instant[] original = originalClocks.get(attId);
            Instant rawIn = (original != null && original[0] != null) ? original[0] : clockIn;
            Instant rawOut = (original != null && original[1] != null) ? original[1] : clockOut;
            long rawMins = Duration.between(rawIn, rawOut).toMinutes()
                    - breakMinutes(originalBreaksByAttendanceId.getOrDefault(attId, List.of()));

            long corrMins = Duration.between(clockIn, clockOut).toMinutes()
                    - breakMinutes(currentBreaksByAttendanceId.getOrDefault(attId, List.of()));

            long[] acc = empAcc.computeIfAbsent(empId, k -> new long[]{branchId, 0L, 0L, 0L});
            acc[1] += 1;
            acc[2] += rawMins;
            acc[3] += corrMins;
        }

        List<EmployeeAttendanceComparisonRow> employeeRows = new ArrayList<>(empAcc.size());
        List<Map<String, Object>> datasetRows = new ArrayList<>(empAcc.size());
        long totalDelta = 0L;

        for (var entry : empAcc.entrySet()) {
            long empId = entry.getKey();
            long[] acc = entry.getValue();
            long branchId = acc[0];
            long shiftDays = acc[1];
            long rawMins = acc[2];
            long corrMins = acc[3];
            long delta = corrMins - rawMins;
            totalDelta += delta;
            String empName = employeeNames.getOrDefault(empId, "직원#" + empId);
            String branchName = branchNames.getOrDefault(branchId, "");

            employeeRows.add(new EmployeeAttendanceComparisonRow(
                    empId, empName, branchId, branchName, shiftDays, rawMins, corrMins, delta
            ));

            var row = new LinkedHashMap<String, Object>();
            row.put("employee_id", empId);
            row.put("employee_name", empName);
            row.put("branch_id", branchId);
            row.put("branch_name", branchName);
            row.put("shift_days", shiftDays);
            row.put("raw_work_minutes", rawMins);
            row.put("illustrative_corrected_minutes", corrMins);
            row.put("correction_delta_minutes", delta);
            datasetRows.add(row);
        }

        var metaRow = new LinkedHashMap<String, Object>();
        metaRow.put("illustrative_correction_delta_minutes", totalDelta);
        metaRow.put("status", "PROVISIONAL_NOT_GOLDEN");

        var dsEmp = new TypedDataset("employeeWorkHours", def.outputs().get("employeeWorkHours"), datasetRows, datasetRows.size(), 0, 20, now, now);
        var dsMeta = new TypedDataset("attendancePolicyMeta", def.outputs().get("attendancePolicyMeta"), List.of(metaRow), 1, 0, 1, now, now);
        var datasets = List.of(dsEmp, dsMeta);

        var view = new ViewSpec(
                CATALOG_VERSION,
                List.of(
                        new ViewSpec.Component(
                                "barChart",
                                new ViewSpec.Ref("BAR_CHART", COMPONENT_VERSION),
                                "employeeWorkHours",
                                Map.of("category", "employee_name", "value", "raw_work_minutes"),
                                Map.of("title", "지난주 직원별 실근무시간(분) 비교", "unit", "분"),
                                List.of()
                        ),
                        new ViewSpec.Component(
                                "table",
                                new ViewSpec.Ref("DATA_TABLE", COMPONENT_VERSION),
                                "employeeWorkHours",
                                Map.of("columns", List.of("employee_id", "employee_name", "branch_id", "branch_name", "shift_days", "raw_work_minutes", "illustrative_corrected_minutes", "correction_delta_minutes")),
                                Map.of("title", "지난주 직원별 근무시간 및 정정 반영 비교"),
                                List.of()
                        )
                ),
                Layout.stack(List.of(Layout.leaf("barChart"), Layout.leaf("table")))
        );

        var notice = new OutcomeNotice(
                "POLICY_PENDING_ATTENDANCE_CORRECTION_AGGREGATION",
                OutcomeNotice.Kind.CLARIFICATION,
                "근무시간 반올림과 근태 조회 범위 정책 확정 전 비교 수치입니다. 원본은 정정 전, 보정 후는 승인된 정정을 반영한 근태입니다.",
                "working-hours aggregation rounding, attendance read scope 정책 확정 필요"
        );
        var envelope = buildEnvelope("POC-010", datasets, view, notice);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "PROVISIONAL_NOT_GOLDEN");
        summary.put("employee_rows", employeeRows);
        summary.put("raw_minutes_formula", "sum(clock_out_at-clock_in_at - actual break interval), "
                + "originals from the earliest approved correction's before_* values");
        summary.put("illustrative_correction_delta_minutes", totalDelta);
        summary.put("needs_decision", List.of(
                "working-hours aggregation rounding",
                "attendance read scope"
        ));

        return new PocExecutionResult(
                "POC-010",
                ID_POC_010,
                "지난주 직원별 근무시간을 비교해줘",
                "POLICY_PENDING_ATTENDANCE_CORRECTION_AGGREGATION",
                List.of("BAR_CHART", "DATA_TABLE"),
                envelope,
                summary
        );
    }

    private static long breakMinutes(List<Instant[]> breaks) {
        long minutes = 0L;
        for (Instant[] interval : breaks) {
            minutes += Duration.between(interval[0], interval[1]).toMinutes();
        }
        return minutes;
    }

    private ResultEnvelope buildEnvelope(
            String requestIdPrefix,
            List<TypedDataset> datasets,
            ViewSpec rawView,
            OutcomeNotice notice
    ) {
        var bound = viewBinder.bind(rawView, datasets);
        var envelope = new ResultEnvelope(
                requestIdPrefix + "-" + UUID.randomUUID(),
                "exec-" + requestIdPrefix,
                "READY",
                datasets,
                bound.view(),
                bound.warnings(),
                null,
                clock.asOf(),
                registry.version(),
                "openstudy-policy-v1",
                List.<FormulaDefinition.Binding>of(),
                List.<FormulaDefinition.DisplayValues>of(),
                notice
        );
        ResultRules.envelope(envelope);
        return envelope;
    }

    @SuppressWarnings("unchecked")
    private Collection<Long> extractBranchScope(UserContext user) {
        if (user == null || user.policyBinding() == null || user.policyBinding().scope() == null) {
            return salesService.allBranchIds();
        }
        Object branchIdsObj = user.policyBinding().scope().get("authorizedBranchIds");
        if (branchIdsObj instanceof Collection<?> col && !col.isEmpty()) {
            List<Long> parsed = new ArrayList<>();
            for (Object item : col) {
                if (item instanceof Number n) {
                    parsed.add(n.longValue());
                } else if (item != null) {
                    parsed.add(Long.parseLong(item.toString()));
                }
            }
            return parsed;
        }
        return salesService.allBranchIds();
    }
}
