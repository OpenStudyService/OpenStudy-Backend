package com.openstudy.modules.sales.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.branch.service.SessionBranchService;
import com.openstudy.modules.sales.dto.request.AggregationCondition;
import com.openstudy.modules.sales.dto.request.BranchRevenueSort;
import com.openstudy.modules.sales.dto.request.LedgerEntryType;
import com.openstudy.modules.sales.dto.request.SalesPurpose;
import com.openstudy.modules.sales.dto.response.BranchRevenue;
import com.openstudy.modules.sales.dto.response.BranchRevenueComparison;
import com.openstudy.modules.sales.dto.response.RevenueSummary;
import com.openstudy.modules.sales.dto.response.SalesLedgerEntry;
import com.openstudy.modules.sales.dto.response.SalesLedgerPage;
import com.openstudy.modules.sales.repository.SalesSourceRepository;
import com.openstudy.modules.sales.repository.SalesSourceRepository.LedgerRow;

/**
 * Revenue, branch comparison and the minimal ledger (#87, aggregation-api.md). This is the service boundary M10 calls
 * with the same {@link AggregationCondition} the HTTP controller builds (M10-D05): the requester is an employee ID and
 * {@link AggregationConditions} reads that employee's current permissions, so no caller can skip the 422/403 rules.
 * <p>Each call is one read-only REPEATABLE READ transaction: the authorization read opens the snapshot, then asOf is
 * taken, then every total, page and metadata number is computed from that same snapshot (FR-AGG-09).</p>
 */
@Service
@RequiredArgsConstructor
public class SalesRevenueService {
    private static final List<SalesPurpose> ALL_PURPOSES = Arrays.asList(SalesPurpose.values());

    private final AggregationConditions conditions;
    private final SalesSourceRepository sources;
    private final AuthorizationService authorization;
    private final SessionBranchService branches;

    /** GET /sales/revenue: totals and the three (or the one filtered) purpose subtotals. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RevenueSummary revenue(long requester, AggregationCondition condition) {
        var scope = conditions.resolve(requester, condition, AggregationConditions.Target.REVENUE, false);
        var tally = new RevenueTally(sources.totals(scope.sourceFilter()));
        var purposes = purposes(scope);
        var totals = tally.of(scope.branchIds(), purposes);
        return new RevenueSummary(AggregationMetas.of(scope, purposes.size(), totals.sourceRows(),
                AggregationMetas.REVENUE_METRICS, null, null), totals.view(), tally.byPurpose(scope.branchIds(), purposes));
    }

    /** GET /sales/revenue/branches: one row per applied branch, zero rows included, in the requested order. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BranchRevenueComparison compareBranches(long requester, AggregationCondition condition, BranchRevenueSort sort,
            ListQuery.Direction direction) {
        var scope = conditions.resolve(requester, condition, AggregationConditions.Target.REVENUE, false);
        var tally = new RevenueTally(sources.totals(scope.sourceFilter()));
        var purposes = purposes(scope);
        Map<Long, String> names = branches.names(scope.branchIds());
        var rows = new ArrayList<BranchRevenue>();
        var ordered = sort == BranchRevenueSort.REQUESTED ? scope.requestedOrder() : scope.branchIds();
        for (long branch : ordered) {
            var amounts = tally.of(List.of(branch), purposes);
            rows.add(new BranchRevenue(Long.toString(branch), names.getOrDefault(branch, ""), amounts.approvedAmount(),
                    amounts.refundAmount(), amounts.net(), amounts.approvedCount(), amounts.refundCount(),
                    amounts.dataStatus(), tally.byPurpose(List.of(branch), purposes)));
        }
        if (sort != BranchRevenueSort.REQUESTED) {
            Comparator<BranchRevenue> order = switch (sort == null ? BranchRevenueSort.NET_AMOUNT : sort) {
                case NET_AMOUNT -> Comparator.comparingLong(BranchRevenue::netAmountKrw);
                case APPROVED_AMOUNT -> Comparator.comparingLong(BranchRevenue::approvedAmountKrw);
                case REFUND_AMOUNT -> Comparator.comparingLong(BranchRevenue::refundAmountKrw);
                case BRANCH_ID, REQUESTED -> (left, right) -> 0;
            };
            // An amount tie falls back to branchId in the same direction (common list rule).
            order = order.thenComparingLong(row -> Long.parseLong(row.branchId()));
            if (direction != ListQuery.Direction.ASC) order = order.reversed();
            rows.sort(order);
        }
        var totals = tally.of(scope.branchIds(), purposes);
        return new BranchRevenueComparison(AggregationMetas.of(scope, rows.size(), totals.sourceRows(),
                AggregationMetas.REVENUE_METRICS, null, null), totals.view(), rows);
    }

    /**
     * GET /sales/ledger: the approval and refund items behind the revenue numbers of the same condition. totals and
     * meta.total cover the whole condition (entryType included), the page only limits items.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SalesLedgerPage ledger(long requester, AggregationCondition condition, LedgerEntryType entryType, ListQuery page) {
        var scope = conditions.resolve(requester, condition, AggregationConditions.Target.REVENUE, false);
        var tally = new RevenueTally(sources.totals(scope.sourceFilter()).stream()
                .filter(total -> entryType == null || total.type() == entryType).toList());
        var totals = tally.of(scope.branchIds(), purposes(scope));
        long total = totals.sourceRows();
        List<SalesLedgerEntry> items = total <= page.offset() ? List.of()
                : sources.ledger(scope.sourceFilter(), entryType, page.direction() != ListQuery.Direction.ASC,
                        page.offset(), page.pageSize()).stream().map(row -> entry(row, scope)).toList();
        return new SalesLedgerPage(items, new PageResponse.Meta(page.page(), page.pageSize(), total),
                AggregationMetas.of(scope, total, total, AggregationMetas.REVENUE_METRICS, null, null), totals.view());
    }

    private SalesLedgerEntry entry(LedgerRow row, AggregationScope scope) {
        boolean approval = row.type() == LedgerEntryType.APPROVAL;
        long sign = approval ? 1 : -1;
        return new SalesLedgerEntry((approval ? "payment-" + row.paymentId() : "refund-" + row.refundId()), row.type().label,
                Long.toString(row.paymentId()), id(row.refundId()), Long.toString(row.branchId()), id(row.processingBranchId()),
                row.purpose().label, id(row.productId()), row.productName(), row.categoryCode(), row.categoryName(),
                (int) sign * row.passQuantity(), Math.multiplyExact(sign, row.amountKrw()), row.approvedAt(), row.refundedAt(),
                row.basisAt(), authorization.permits(scope.requester(), row.branchId(), BranchAction.READ_PAYMENTS));
    }

    private static List<SalesPurpose> purposes(AggregationScope scope) {
        return scope.purpose() == null ? ALL_PURPOSES : List.of(scope.purpose());
    }

    private static String id(Long value) { return value == null ? null : value.toString(); }
}
