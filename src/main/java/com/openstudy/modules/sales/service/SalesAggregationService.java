package com.openstudy.modules.sales.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.ListQuery;
import com.openstudy.modules.sales.dto.request.AggregationCondition;
import com.openstudy.modules.sales.dto.request.AggregationGranularity;
import com.openstudy.modules.sales.dto.request.BranchRevenueSort;
import com.openstudy.modules.sales.dto.request.SalesPurpose;
import com.openstudy.modules.sales.dto.request.TrendMeasure;
import com.openstudy.modules.sales.dto.response.PassProductSales;
import com.openstudy.modules.sales.dto.response.RevenueAmounts;

/**
 * The shapes the registered AI analyses read (M10), computed by the M9 sales services with the same
 * {@link AggregationCondition} the sales API builds (ADR-M10-001 M10-D05). Before #95's fix this class had its own
 * queries that selected payments by their row creation time, so an AI answer could differ from the sales screen; now
 * every number comes from {@link SalesRevenueService}, {@link SalesTrendService} and {@link PassSalesService}, which
 * also apply the requester's sales:read on every branch.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SalesAggregationService {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public static final String ATTRIBUTION_ORIGINAL_PAYMENT_BRANCH = "ORIGINAL_PAYMENT_BRANCH";

    /** The AI analyses' names for the two refund bases of M9-D02. */
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

        com.openstudy.modules.sales.dto.request.RefundBasis m9() {
            return this == ORIGINAL_PAYMENT_DATE ? com.openstudy.modules.sales.dto.request.RefundBasis.PAYMENT_DATE
                    : com.openstudy.modules.sales.dto.request.RefundBasis.PROCESSED_DATE;
        }
    }

    public record BranchSalesRow(long branchId, String branchName, long approvedKrw, long refundKrw, long netKrw,
            long approvedCount, long refundCount) {}

    public record BranchSalesSummary(List<BranchSalesRow> rows, long totalApprovedKrw, long totalRefundKrw, long totalNetKrw,
            long totalApprovedCount, long totalRefundCount, RefundBasis refundBasis, String attribution) {}

    public record DailySalesRow(String dateKst, long approvedKrw, long refundKrw, long netKrw, long approvedCount,
            long refundCount) {}

    public record DailySalesSummary(List<DailySalesRow> days, boolean zeroFilled, int daysCount, long totalApprovedKrw,
            long totalRefundKrw, long totalNetKrw, RefundBasis refundBasis, String attribution) {}

    public record PurposeSalesRow(String purpose, long approvedKrw, long refundKrw, long netKrw, long approvedCount,
            long refundCount, double sharePct) {}

    public record PurposeSalesSummary(List<PurposeSalesRow> purposeRows, String winnerPurpose, String rankingMetric,
            long totalApprovedKrw, long totalRefundKrw, long totalNetKrw, RefundBasis refundBasis, String attribution) {}

    private final EntityManager entityManager;
    private final SalesRevenueService revenue;
    private final SalesTrendService trends;
    private final PassSalesService passes;

    /** Every branch, for the non-sales analyses' own reads. Sales numbers never use it: M9 takes the scope. */
    public List<Long> allBranchIds() {
        return entityManager.createQuery("SELECT b.id FROM Branch b ORDER BY b.id ASC", Long.class).getResultList();
    }

    /** One row per branch (zero rows kept) in ascending branch ID: GET /sales/revenue/branches with the same condition. */
    public BranchSalesSummary aggregateByBranch(long requester, Collection<Long> branchIds, Instant startInclusive,
            Instant endExclusive, RefundBasis refundBasis) {
        var comparison = revenue.compareBranches(requester, condition(branchIds, startInclusive, endExclusive, refundBasis, null),
                BranchRevenueSort.BRANCH_ID, ListQuery.Direction.ASC);
        var rows = comparison.items().stream().map(item -> new BranchSalesRow(Long.parseLong(item.branchId()), item.branchName(),
                item.approvedAmountKrw(), item.refundAmountKrw(), item.netAmountKrw(), item.approvedPaymentCount(),
                item.refundCount())).toList();
        RevenueAmounts totals = comparison.totals();
        return new BranchSalesSummary(rows, totals.approvedAmountKrw(), totals.refundAmountKrw(), totals.netAmountKrw(),
                totals.approvedPaymentCount(), totals.refundCount(), refundBasis, ATTRIBUTION_ORIGINAL_PAYMENT_BRANCH);
    }

    /** Daily buckets in Asia/Seoul, empty days as zero (M9-D05): GET /sales/trend?granularity=day&measure=revenue. */
    public DailySalesSummary aggregateDailyTrend(long requester, Collection<Long> branchIds, LocalDate startDateKst,
            LocalDate endDateExclusiveKst, RefundBasis refundBasis) {
        var condition = condition(branchIds, startDateKst.atStartOfDay(KST).toInstant(),
                endDateExclusiveKst.atStartOfDay(KST).toInstant(), refundBasis, null).withGranularity(AggregationGranularity.DAY);
        var trend = trends.trend(requester, condition, TrendMeasure.REVENUE);
        var days = new ArrayList<DailySalesRow>();
        long approved = 0, refunded = 0;
        for (var bucket : trend.items()) {
            var amounts = bucket.revenue();
            days.add(new DailySalesRow(bucket.bucketStart().atZone(KST).toLocalDate().toString(), amounts.approvedAmountKrw(),
                    amounts.refundAmountKrw(), amounts.netAmountKrw(), amounts.approvedPaymentCount(), amounts.refundCount()));
            approved += amounts.approvedAmountKrw();
            refunded += amounts.refundAmountKrw();
        }
        return new DailySalesSummary(List.copyOf(days), true, days.size(), approved, refunded, approved - refunded, refundBasis,
                ATTRIBUTION_ORIGINAL_PAYMENT_BRANCH);
    }

    /** The three purposes ranked by net: GET /sales/revenue's byPurpose with the same condition. */
    public PurposeSalesSummary aggregateByPurpose(long requester, Collection<Long> branchIds, Instant startInclusive,
            Instant endExclusive, RefundBasis refundBasis) {
        var summary = revenue.revenue(requester, condition(branchIds, startInclusive, endExclusive, refundBasis, null));
        long totalNet = summary.totals().netAmountKrw();
        var rows = new ArrayList<PurposeSalesRow>();
        for (var purpose : summary.byPurpose()) {
            double share = totalNet > 0 ? Math.round((purpose.netAmountKrw() * 1000.0) / totalNet) / 10.0 : 0.0;
            rows.add(new PurposeSalesRow(code(purpose.purpose()), purpose.approvedAmountKrw(), purpose.refundAmountKrw(),
                    purpose.netAmountKrw(), purpose.approvedPaymentCount(), purpose.refundCount(), share));
        }
        rows.sort(Comparator.comparingLong(PurposeSalesRow::netKrw).reversed());
        return new PurposeSalesSummary(List.copyOf(rows), rows.isEmpty() ? "PASS" : rows.getFirst().purpose(), "net_krw",
                summary.totals().approvedAmountKrw(), summary.totals().refundAmountKrw(), totalNet, refundBasis,
                ATTRIBUTION_ORIGINAL_PAYMENT_BRANCH);
    }

    /** The top pass products by net sold quantity: GET /sales/passes/products with the same condition (#88 ranking). */
    public List<PassProductSales> topPassProducts(long requester, Collection<Long> branchIds, Instant startInclusive,
            Instant endExclusive, int limit) {
        return topPassProducts(requester, branchIds, startInclusive, endExclusive, limit, null);
    }

    /** The same ranking narrowed to one product (M9 product filter), or every product when {@code productId} is null. */
    public List<PassProductSales> topPassProducts(long requester, Collection<Long> branchIds, Instant startInclusive,
            Instant endExclusive, int limit, Long productId) {
        var condition = AggregationCondition.passes(sorted(branchIds), startInclusive, endExclusive, null, productId, null);
        return passes.products(requester, condition, new ListQuery(1, limit, "", null, ListQuery.Direction.DESC)).items();
    }

    private static AggregationCondition condition(Collection<Long> branchIds, Instant from, Instant to, RefundBasis basis,
            SalesPurpose purpose) {
        return AggregationCondition.revenue(sorted(branchIds), from, to, (basis == null ? RefundBasis.REFUND_PROCESSED_AT : basis).m9(),
                purpose);
    }

    /** The analyses always name their branches; an empty scope is a caller error, never "every branch". */
    private static List<Long> sorted(Collection<Long> branchIds) {
        if (branchIds == null || branchIds.isEmpty()) {
            throw new IllegalArgumentException("A sales analysis needs its branches");
        }
        return branchIds.stream().distinct().sorted().toList();
    }

    private static String code(String label) {
        for (var purpose : SalesPurpose.values()) if (purpose.label.equals(label) || purpose.name().equals(label)) return purpose.name();
        return label;
    }
}
