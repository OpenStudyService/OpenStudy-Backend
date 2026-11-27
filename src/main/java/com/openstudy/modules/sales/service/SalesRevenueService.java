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
import com.openstudy.modules.sales.dto.response.PurposeRevenue;
import com.openstudy.modules.sales.dto.response.RevenueSummary;
import com.openstudy.modules.sales.dto.response.SalesLedgerEntry;
import com.openstudy.modules.sales.dto.response.SalesLedgerPage;
import com.openstudy.modules.sales.repository.SalesSourceRepository;
import com.openstudy.modules.sales.repository.SalesSourceRepository.LedgerRow;
import com.openstudy.modules.sales.repository.SalesSourceRepository.SourceTotal;
import com.openstudy.modules.sales.service.RevenueTally.Amounts;

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
    private static final String PAYMENT_ENTRY_PREFIX = "payment-";
    private static final String REFUND_ENTRY_PREFIX = "refund-";
    private static final String UNKNOWN_BRANCH_NAME = "";

    private final AggregationConditions aggregationConditions;
    private final SalesSourceRepository salesSourceRepository;
    private final AuthorizationService authorizationService;
    private final SessionBranchService sessionBranchService;

    /** GET /sales/revenue: totals and the three (or the one filtered) purpose subtotals. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RevenueSummary revenue(long requester, AggregationCondition condition) {
        AggregationScope scope = aggregationConditions.resolve(
                requester,
                condition,
                AggregationConditions.Target.REVENUE,
                false
        );
        RevenueTally tally = new RevenueTally(salesSourceRepository.totals(scope.sourceFilter()));
        List<SalesPurpose> purposes = findPurposes(scope);
        Amounts totals = tally.sumAmounts(scope.branchIds(), purposes);

        return new RevenueSummary(
                AggregationMetas.of(
                        scope,
                        purposes.size(),
                        totals.sourceRows(),
                        AggregationMetas.REVENUE_METRICS,
                        null,
                        null
                ),
                totals.toView(),
                tally.toPurposeRows(scope.branchIds(), purposes)
        );
    }

    /** GET /sales/revenue/branches: one row per applied branch, zero rows included, in the requested order. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BranchRevenueComparison compareBranches(
            long requester,
            AggregationCondition condition,
            BranchRevenueSort sort,
            ListQuery.Direction direction
    ) {
        AggregationScope scope = aggregationConditions.resolve(
                requester,
                condition,
                AggregationConditions.Target.REVENUE,
                false
        );
        RevenueTally tally = new RevenueTally(salesSourceRepository.totals(scope.sourceFilter()));
        List<SalesPurpose> purposes = findPurposes(scope);
        Map<Long, String> names = sessionBranchService.names(scope.branchIds());
        List<BranchRevenue> rows = new ArrayList<>();
        List<Long> ordered = sort == BranchRevenueSort.REQUESTED ? scope.requestedOrder() : scope.branchIds();

        for (long branch : ordered) {
            Amounts amounts = tally.sumAmounts(List.of(branch), purposes);
            List<PurposeRevenue> byPurpose = tally.toPurposeRows(List.of(branch), purposes);
            rows.add(new BranchRevenue(
                    Long.toString(branch),
                    names.getOrDefault(branch, UNKNOWN_BRANCH_NAME),
                    amounts.approvedAmount(),
                    amounts.refundAmount(),
                    amounts.net(),
                    amounts.approvedCount(),
                    amounts.refundCount(),
                    amounts.dataStatus(),
                    byPurpose
            ));
        }

        if (sort != BranchRevenueSort.REQUESTED) {
            rows.sort(createRowOrder(sort, direction));
        }

        Amounts totals = tally.sumAmounts(scope.branchIds(), purposes);

        return new BranchRevenueComparison(
                AggregationMetas.of(
                        scope,
                        rows.size(),
                        totals.sourceRows(),
                        AggregationMetas.REVENUE_METRICS,
                        null,
                        null
                ),
                totals.toView(),
                rows
        );
    }

    /**
     * GET /sales/ledger: the approval and refund items behind the revenue numbers of the same condition. totals and
     * meta.total cover the whole condition (entryType included), the page only limits items.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SalesLedgerPage ledger(
            long requester,
            AggregationCondition condition,
            LedgerEntryType entryType,
            ListQuery page
    ) {
        AggregationScope scope = aggregationConditions.resolve(
                requester,
                condition,
                AggregationConditions.Target.REVENUE,
                false
        );
        List<SourceTotal> entryTotals = salesSourceRepository.totals(scope.sourceFilter())
                .stream()
                .filter(total -> entryType == null || total.type() == entryType)
                .toList();
        RevenueTally tally = new RevenueTally(entryTotals);
        Amounts totals = tally.sumAmounts(scope.branchIds(), findPurposes(scope));
        long total = totals.sourceRows();
        boolean pageBeyondTotal = total <= page.offset();
        List<SalesLedgerEntry> items = pageBeyondTotal ? List.of() : findLedgerEntries(scope, entryType, page);

        return new SalesLedgerPage(
                items,
                new PageResponse.Meta(page.page(), page.pageSize(), total),
                AggregationMetas.of(scope, total, total, AggregationMetas.REVENUE_METRICS, null, null),
                totals.toView()
        );
    }

    private List<SalesLedgerEntry> findLedgerEntries(
            AggregationScope scope,
            LedgerEntryType entryType,
            ListQuery page
    ) {
        boolean newestFirst = page.direction() != ListQuery.Direction.ASC;

        return salesSourceRepository.ledger(
                        scope.sourceFilter(),
                        entryType,
                        newestFirst,
                        page.offset(),
                        page.pageSize()
                )
                .stream()
                .map(row -> toLedgerEntry(row, scope))
                .toList();
    }

    private static Comparator<BranchRevenue> createRowOrder(BranchRevenueSort sort, ListQuery.Direction direction) {
        BranchRevenueSort appliedSort = sort == null ? BranchRevenueSort.NET_AMOUNT : sort;
        Comparator<BranchRevenue> order = switch (appliedSort) {
            case NET_AMOUNT -> Comparator.comparingLong(BranchRevenue::netAmountKrw);
            case APPROVED_AMOUNT -> Comparator.comparingLong(BranchRevenue::approvedAmountKrw);
            case REFUND_AMOUNT -> Comparator.comparingLong(BranchRevenue::refundAmountKrw);
            case BRANCH_ID, REQUESTED -> (left, right) -> 0;
        };
        // An amount tie falls back to branchId in the same direction (common list rule).
        order = order.thenComparingLong(row -> Long.parseLong(row.branchId()));

        if (direction != ListQuery.Direction.ASC) {
            order = order.reversed();
        }

        return order;
    }

    private SalesLedgerEntry toLedgerEntry(LedgerRow row, AggregationScope scope) {
        boolean approval = row.type() == LedgerEntryType.APPROVAL;
        long sign = approval ? 1 : -1;
        String entryId = approval
                ? PAYMENT_ENTRY_PREFIX + row.paymentId()
                : REFUND_ENTRY_PREFIX + row.refundId();

        return new SalesLedgerEntry(
                entryId,
                row.type().label,
                Long.toString(row.paymentId()),
                toText(row.refundId()),
                Long.toString(row.branchId()),
                toText(row.processingBranchId()),
                row.purpose().label,
                toText(row.productId()),
                row.productName(),
                row.categoryCode(),
                row.categoryName(),
                (int) sign * row.passQuantity(),
                Math.multiplyExact(sign, row.amountKrw()),
                row.approvedAt(),
                row.refundedAt(),
                row.basisAt(),
                authorizationService.permits(scope.requester(), row.branchId(), BranchAction.READ_PAYMENTS)
        );
    }

    private static List<SalesPurpose> findPurposes(AggregationScope scope) {
        return scope.purpose() == null ? ALL_PURPOSES : List.of(scope.purpose());
    }

    private static String toText(Long value) {
        return value == null ? null : value.toString();
    }
}
