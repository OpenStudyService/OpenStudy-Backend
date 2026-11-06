package com.openstudy.modules.sales.service;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.sales.dto.response.SalesResponse;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared M9 & M10 sales aggregation service implementing {@code ADR-M9-001} and {@code ADR-M10-001} D05 ({@code OS-DL-0402}).
 * <ul>
 *   <li>Approved sales include original transactions that were approved ({@code APPROVED} or later {@code REFUNDED});
 *       {@code FAILED} and {@code PENDING} payments are excluded.</li>
 *   <li>Refund attribution is always {@code ORIGINAL_PAYMENT_BRANCH} ({@code payment.branchId}).</li>
 *   <li>Supports both {@link RefundBasis#REFUND_PROCESSED_AT} (default, grouped/filtered by {@code refund.refundedAt})
 *       and {@link RefundBasis#ORIGINAL_PAYMENT_DATE} (filtered by {@code payment.createdAt}).</li>
 *   <li>Zero-sales branches and zero-sales KST calendar days are preserved with {@code 0} values.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class SalesAggregationService {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public static final String ATTRIBUTION_ORIGINAL_PAYMENT_BRANCH = "ORIGINAL_PAYMENT_BRANCH";

    public enum RefundBasis {
        REFUND_PROCESSED_AT,
        ORIGINAL_PAYMENT_DATE;

        public static RefundBasis fromParam(String raw) {
            if (raw == null || raw.isBlank()) {
                return REFUND_PROCESSED_AT;
            }
            return switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "PROCESSED", "REFUND_PROCESSED_AT" -> REFUND_PROCESSED_AT;
                case "ORIGINAL", "ORIGINAL_PAYMENT_DATE" -> ORIGINAL_PAYMENT_DATE;
                default -> throw ApiException.validation("basis", "허용된 환불 기준(processed, original)을 선택해 주세요.");
            };
        }
    }

    public record BranchSalesRow(
            long branchId,
            String branchName,
            long approvedKrw,
            long refundKrw,
            long netKrw,
            long approvedCount,
            long refundCount
    ) {
        public SalesResponse.BranchRow toDto() {
            return new SalesResponse.BranchRow(
                    Long.toString(branchId),
                    branchName,
                    approvedKrw,
                    refundKrw,
                    netKrw,
                    approvedCount,
                    refundCount
            );
        }
    }

    public record BranchSalesSummary(
            List<BranchSalesRow> rows,
            long totalApprovedKrw,
            long totalRefundKrw,
            long totalNetKrw,
            long totalApprovedCount,
            long totalRefundCount,
            RefundBasis refundBasis,
            String attribution
    ) {}

    public record DailySalesRow(
            String dateKst,
            long approvedKrw,
            long refundKrw,
            long netKrw,
            long approvedCount,
            long refundCount
    ) {}

    public record DailySalesSummary(
            List<DailySalesRow> days,
            boolean zeroFilled,
            int daysCount,
            long totalApprovedKrw,
            long totalRefundKrw,
            long totalNetKrw,
            RefundBasis refundBasis,
            String attribution
    ) {}

    public record PurposeSalesRow(
            String purpose,
            long approvedKrw,
            long refundKrw,
            long netKrw,
            long approvedCount,
            long refundCount,
            double sharePct
    ) {}

    public record PurposeSalesSummary(
            List<PurposeSalesRow> purposeRows,
            String winnerPurpose,
            String rankingMetric,
            long totalApprovedKrw,
            long totalRefundKrw,
            long totalNetKrw,
            RefundBasis refundBasis,
            String attribution
    ) {}

    private final EntityManager entityManager;
    private final AuthorizationService authorization;
    private final Instant defaultAsOf;

    public SalesAggregationService(
            EntityManager entityManager,
            AuthorizationService authorization,
            @Value("${openstudy.ai.fixed-as-of:2026-11-02T05:00:00Z}") String fixedAsOfText
    ) {
        this.entityManager = entityManager;
        this.authorization = authorization;
        this.defaultAsOf = (fixedAsOfText == null || fixedAsOfText.isBlank())
                ? Instant.parse("2026-11-02T05:00:00Z")
                : (fixedAsOfText.endsWith("Z") || fixedAsOfText.endsWith("z")
                        ? Instant.parse(fixedAsOfText)
                        : OffsetDateTime.parse(fixedAsOfText).toInstant());
    }

    public SalesResponse queryForApi(long actorId, String scope, String start, String end, String basis) {
        Set<Long> branchIds = authorization.scope(actorId, scope, BranchAction.READ_SALES);
        RefundBasis refundBasis = RefundBasis.fromParam(basis);

        LocalDate todayKst = defaultAsOf.atZone(KST).toLocalDate();
        LocalDate startDate = (start == null || start.isBlank())
                ? YearMonth.from(todayKst).atDay(1)
                : parseLocalDate("start", start);
        LocalDate endDateInclusive = (end == null || end.isBlank())
                ? todayKst
                : parseLocalDate("end", end);

        if (endDateInclusive.isBefore(startDate)) {
            throw ApiException.validation("end", "종료일은 시작일 이후여야 합니다.");
        }
        long spanDays = ChronoUnit.DAYS.between(startDate, endDateInclusive) + 1;
        if (spanDays > 366) {
            throw ApiException.validation("end", "조회 기간은 최대 366일까지 지정할 수 있습니다.");
        }

        Instant startInclusive = startDate.atStartOfDay(KST).toInstant();
        Instant endExclusive = endDateInclusive.plusDays(1).atStartOfDay(KST).toInstant();
        BranchSalesSummary summary = aggregateByBranch(branchIds, startInclusive, endExclusive, refundBasis);

        return new SalesResponse(
                summary.rows().stream().map(BranchSalesRow::toDto).toList(),
                new SalesResponse.Totals(
                        summary.totalApprovedKrw(),
                        summary.totalRefundKrw(),
                        summary.totalNetKrw(),
                        summary.totalApprovedCount(),
                        summary.totalRefundCount()
                ),
                summary.refundBasis().name(),
                summary.attribution(),
                startDate.toString(),
                endDateInclusive.toString()
        );
    }

    public List<Long> allBranchIds() {
        List<Long> demoIds = entityManager.createQuery(
                        "SELECT b.id FROM Branch b WHERE b.id >= 9100001 ORDER BY b.id ASC", Long.class)
                .getResultList();
        if (!demoIds.isEmpty()) {
            return demoIds;
        }
        return entityManager.createQuery("SELECT b.id FROM Branch b ORDER BY b.id ASC", Long.class)
                .getResultList();
    }

    /**
     * Branch-level sales comparison (`POC-001` & M9 `GET /api/sales`).
     * Preserves zero-sales branches (`9100003` `신규점 (매출 없음)`) in ascending `branchId` order.
     */
    public BranchSalesSummary aggregateByBranch(
            Collection<Long> branchIds,
            Instant startInclusive,
            Instant endExclusive,
            RefundBasis refundBasis
    ) {
        Collection<Long> effectiveBranches = (branchIds == null || branchIds.isEmpty()) ? allBranchIds() : branchIds;
        if (effectiveBranches.isEmpty()) {
            return new BranchSalesSummary(List.of(), 0L, 0L, 0L, 0L, 0L, refundBasis, ATTRIBUTION_ORIGINAL_PAYMENT_BRANCH);
        }

        List<Object[]> branchTuples = entityManager.createQuery(
                        "SELECT b.id, b.name FROM Branch b WHERE b.id IN :branchIds ORDER BY b.id ASC",
                        Object[].class)
                .setParameter("branchIds", effectiveBranches)
                .getResultList();

        Map<Long, String> branchNames = new LinkedHashMap<>();
        Map<Long, long[]> accumulators = new LinkedHashMap<>();
        for (Object[] tuple : branchTuples) {
            long id = ((Number) tuple[0]).longValue();
            String name = String.valueOf(tuple[1]);
            branchNames.put(id, name);
            // [0]=approvedKrw, [1]=refundKrw, [2]=approvedCount, [3]=refundCount
            accumulators.put(id, new long[4]);
        }

        List<Object[]> paymentTuples = entityManager.createQuery(
                        "SELECT p.branchId, p.amountKrw, p.status FROM Payment p "
                                + "WHERE p.branchId IN :branchIds AND p.createdAt >= :startInclusive AND p.createdAt < :endExclusive",
                        Object[].class)
                .setParameter("branchIds", effectiveBranches)
                .setParameter("startInclusive", startInclusive)
                .setParameter("endExclusive", endExclusive)
                .getResultList();

        for (Object[] tuple : paymentTuples) {
            long branchId = ((Number) tuple[0]).longValue();
            long amountKrw = ((Number) tuple[1]).longValue();
            String status = String.valueOf(tuple[2]);
            if (isApprovedTransaction(status)) {
                long[] acc = accumulators.get(branchId);
                if (acc != null) {
                    acc[0] += amountKrw;
                    acc[2] += 1;
                }
            }
        }

        List<Object[]> refundTuples = queryRefunds(effectiveBranches, startInclusive, endExclusive, refundBasis);
        for (Object[] tuple : refundTuples) {
            long originalBranchId = ((Number) tuple[0]).longValue();
            long refundAmountKrw = ((Number) tuple[1]).longValue();
            String paymentStatus = String.valueOf(tuple[2]);
            if (isApprovedTransaction(paymentStatus)) {
                long[] acc = accumulators.get(originalBranchId);
                if (acc != null) {
                    acc[1] += refundAmountKrw;
                    acc[3] += 1;
                }
            }
        }

        List<BranchSalesRow> rows = new ArrayList<>(branchNames.size());
        long totalApproved = 0L;
        long totalRefund = 0L;
        long totalApprovedCount = 0L;
        long totalRefundCount = 0L;

        for (var entry : branchNames.entrySet()) {
            long branchId = entry.getKey();
            long[] acc = accumulators.get(branchId);
            long approved = acc[0];
            long refund = acc[1];
            long net = approved - refund;
            long approvedCnt = acc[2];
            long refundCnt = acc[3];
            rows.add(new BranchSalesRow(branchId, entry.getValue(), approved, refund, net, approvedCnt, refundCnt));
            totalApproved += approved;
            totalRefund += refund;
            totalApprovedCount += approvedCnt;
            totalRefundCount += refundCnt;
        }

        return new BranchSalesSummary(
                List.copyOf(rows),
                totalApproved,
                totalRefund,
                totalApproved - totalRefund,
                totalApprovedCount,
                totalRefundCount,
                refundBasis,
                ATTRIBUTION_ORIGINAL_PAYMENT_BRANCH
        );
    }

    /**
     * Daily sales trend in KST with mandatory zero-fill across all calendar days in {@code [startDateKst, endDateExclusiveKst)}
     * ({@code POC-007}, {@code ADR-M9-001} D05).
     */
    public DailySalesSummary aggregateDailyTrend(
            Collection<Long> branchIds,
            LocalDate startDateKst,
            LocalDate endDateExclusiveKst,
            RefundBasis refundBasis
    ) {
        Collection<Long> effectiveBranches = (branchIds == null || branchIds.isEmpty()) ? allBranchIds() : branchIds;
        Instant startInclusive = startDateKst.atStartOfDay(KST).toInstant();
        Instant endExclusive = endDateExclusiveKst.atStartOfDay(KST).toInstant();

        Map<LocalDate, long[]> dayAccumulators = new LinkedHashMap<>();
        for (LocalDate d = startDateKst; d.isBefore(endDateExclusiveKst); d = d.plusDays(1)) {
            // [0]=approvedKrw, [1]=refundKrw, [2]=approvedCount, [3]=refundCount
            dayAccumulators.put(d, new long[4]);
        }

        if (!effectiveBranches.isEmpty()) {
            List<Object[]> paymentTuples = entityManager.createQuery(
                            "SELECT p.createdAt, p.amountKrw, p.status FROM Payment p "
                                    + "WHERE p.branchId IN :branchIds AND p.createdAt >= :startInclusive AND p.createdAt < :endExclusive",
                            Object[].class)
                    .setParameter("branchIds", effectiveBranches)
                    .setParameter("startInclusive", startInclusive)
                    .setParameter("endExclusive", endExclusive)
                    .getResultList();

            for (Object[] tuple : paymentTuples) {
                Instant createdAt = (Instant) tuple[0];
                long amountKrw = ((Number) tuple[1]).longValue();
                String status = String.valueOf(tuple[2]);
                if (isApprovedTransaction(status)) {
                    LocalDate dayKst = createdAt.atZone(KST).toLocalDate();
                    long[] acc = dayAccumulators.get(dayKst);
                    if (acc != null) {
                        acc[0] += amountKrw;
                        acc[2] += 1;
                    }
                }
            }

            List<Object[]> refundTuples = queryRefundsWithTimestamps(effectiveBranches, startInclusive, endExclusive, refundBasis);
            for (Object[] tuple : refundTuples) {
                long refundAmountKrw = ((Number) tuple[0]).longValue();
                String paymentStatus = String.valueOf(tuple[1]);
                Instant effectiveInstant = (Instant) tuple[2];
                if (isApprovedTransaction(paymentStatus)) {
                    LocalDate dayKst = effectiveInstant.atZone(KST).toLocalDate();
                    long[] acc = dayAccumulators.get(dayKst);
                    if (acc != null) {
                        acc[1] += refundAmountKrw;
                        acc[3] += 1;
                    }
                }
            }
        }

        List<DailySalesRow> days = new ArrayList<>(dayAccumulators.size());
        long totalApproved = 0L;
        long totalRefund = 0L;
        for (var entry : dayAccumulators.entrySet()) {
            long[] acc = entry.getValue();
            long approved = acc[0];
            long refund = acc[1];
            long net = approved - refund;
            days.add(new DailySalesRow(entry.getKey().toString(), approved, refund, net, acc[2], acc[3]));
            totalApproved += approved;
            totalRefund += refund;
        }

        return new DailySalesSummary(
                List.copyOf(days),
                true,
                days.size(),
                totalApproved,
                totalRefund,
                totalApproved - totalRefund,
                refundBasis,
                ATTRIBUTION_ORIGINAL_PAYMENT_BRANCH
        );
    }

    /**
     * Sales breakdown by item type / payment purpose ({@code PASS}, {@code ROOM}, {@code LOCKER})
     * ranked by {@code net_krw DESC} ({@code POC-008}).
     */
    public PurposeSalesSummary aggregateByPurpose(
            Collection<Long> branchIds,
            Instant startInclusive,
            Instant endExclusive,
            RefundBasis refundBasis
    ) {
        Collection<Long> effectiveBranches = (branchIds == null || branchIds.isEmpty()) ? allBranchIds() : branchIds;
        Map<String, long[]> purposeAcc = new LinkedHashMap<>();
        for (String purpose : List.of("PASS", "ROOM", "LOCKER")) {
            // [0]=approvedKrw, [1]=refundKrw, [2]=approvedCount, [3]=refundCount
            purposeAcc.put(purpose, new long[4]);
        }

        if (!effectiveBranches.isEmpty()) {
            List<Object[]> paymentTuples = entityManager.createQuery(
                            "SELECT p.purpose, p.amountKrw, p.status FROM Payment p "
                                    + "WHERE p.branchId IN :branchIds AND p.createdAt >= :startInclusive AND p.createdAt < :endExclusive",
                            Object[].class)
                    .setParameter("branchIds", effectiveBranches)
                    .setParameter("startInclusive", startInclusive)
                    .setParameter("endExclusive", endExclusive)
                    .getResultList();

            for (Object[] tuple : paymentTuples) {
                String purpose = String.valueOf(tuple[0]);
                long amountKrw = ((Number) tuple[1]).longValue();
                String status = String.valueOf(tuple[2]);
                if (isApprovedTransaction(status)) {
                    long[] acc = purposeAcc.computeIfAbsent(purpose, k -> new long[4]);
                    acc[0] += amountKrw;
                    acc[2] += 1;
                }
            }

            List<Object[]> refundTuples = queryRefundsByPurpose(effectiveBranches, startInclusive, endExclusive, refundBasis);
            for (Object[] tuple : refundTuples) {
                String purpose = String.valueOf(tuple[0]);
                long refundAmountKrw = ((Number) tuple[1]).longValue();
                String paymentStatus = String.valueOf(tuple[2]);
                if (isApprovedTransaction(paymentStatus)) {
                    long[] acc = purposeAcc.computeIfAbsent(purpose, k -> new long[4]);
                    acc[1] += refundAmountKrw;
                    acc[3] += 1;
                }
            }
        }

        long totalApproved = 0L;
        long totalRefund = 0L;
        for (long[] acc : purposeAcc.values()) {
            totalApproved += acc[0];
            totalRefund += acc[1];
        }
        long totalNet = totalApproved - totalRefund;

        List<PurposeSalesRow> rows = new ArrayList<>(purposeAcc.size());
        for (var entry : purposeAcc.entrySet()) {
            long[] acc = entry.getValue();
            long approved = acc[0];
            long refund = acc[1];
            long net = approved - refund;
            double sharePct = totalNet > 0 ? Math.round((net * 1000.0) / totalNet) / 10.0 : 0.0;
            rows.add(new PurposeSalesRow(entry.getKey(), approved, refund, net, acc[2], acc[3], sharePct));
        }

        rows.sort(Comparator.comparingLong(PurposeSalesRow::netKrw).reversed());
        String winner = rows.isEmpty() ? "PASS" : rows.getFirst().purpose();

        return new PurposeSalesSummary(
                List.copyOf(rows),
                winner,
                "net_krw",
                totalApproved,
                totalRefund,
                totalNet,
                refundBasis,
                ATTRIBUTION_ORIGINAL_PAYMENT_BRANCH
        );
    }

    private List<Object[]> queryRefunds(
            Collection<Long> branchIds,
            Instant startInclusive,
            Instant endExclusive,
            RefundBasis refundBasis
    ) {
        String timeFilter = refundBasis == RefundBasis.REFUND_PROCESSED_AT
                ? "r.refundedAt >= :startInclusive AND r.refundedAt < :endExclusive"
                : "p.createdAt >= :startInclusive AND p.createdAt < :endExclusive";
        return entityManager.createQuery(
                        "SELECT p.branchId, r.amountKrw, p.status FROM Refund r, Payment p "
                                + "WHERE p.id = r.paymentId AND p.branchId IN :branchIds AND " + timeFilter,
                        Object[].class)
                .setParameter("branchIds", branchIds)
                .setParameter("startInclusive", startInclusive)
                .setParameter("endExclusive", endExclusive)
                .getResultList();
    }

    private List<Object[]> queryRefundsWithTimestamps(
            Collection<Long> branchIds,
            Instant startInclusive,
            Instant endExclusive,
            RefundBasis refundBasis
    ) {
        String timeField = refundBasis == RefundBasis.REFUND_PROCESSED_AT ? "r.refundedAt" : "p.createdAt";
        return entityManager.createQuery(
                        "SELECT r.amountKrw, p.status, " + timeField + " FROM Refund r, Payment p "
                                + "WHERE p.id = r.paymentId AND p.branchId IN :branchIds "
                                + "AND " + timeField + " >= :startInclusive AND " + timeField + " < :endExclusive",
                        Object[].class)
                .setParameter("branchIds", branchIds)
                .setParameter("startInclusive", startInclusive)
                .setParameter("endExclusive", endExclusive)
                .getResultList();
    }

    private List<Object[]> queryRefundsByPurpose(
            Collection<Long> branchIds,
            Instant startInclusive,
            Instant endExclusive,
            RefundBasis refundBasis
    ) {
        String timeFilter = refundBasis == RefundBasis.REFUND_PROCESSED_AT
                ? "r.refundedAt >= :startInclusive AND r.refundedAt < :endExclusive"
                : "p.createdAt >= :startInclusive AND p.createdAt < :endExclusive";
        return entityManager.createQuery(
                        "SELECT p.purpose, r.amountKrw, p.status FROM Refund r, Payment p "
                                + "WHERE p.id = r.paymentId AND p.branchId IN :branchIds AND " + timeFilter,
                        Object[].class)
                .setParameter("branchIds", branchIds)
                .setParameter("startInclusive", startInclusive)
                .setParameter("endExclusive", endExclusive)
                .getResultList();
    }

    public static boolean isApprovedTransaction(String status) {
        return "APPROVED".equals(status) || "REFUNDED".equals(status);
    }

    private static LocalDate parseLocalDate(String field, String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw ApiException.validation(field, "날짜 형식(YYYY-MM-DD)을 확인해 주세요.");
        }
    }
}
