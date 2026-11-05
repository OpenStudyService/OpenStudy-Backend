package com.openstudy.modules.sales.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.openstudy.modules.sales.dto.request.LedgerEntryType;
import com.openstudy.modules.sales.dto.request.SalesPurpose;
import com.openstudy.modules.sales.dto.response.PurposeRevenue;
import com.openstudy.modules.sales.dto.response.RevenueAmounts;
import com.openstudy.modules.sales.repository.SalesSourceRepository.SourceTotal;

/**
 * Sales metrics of one source-total read (x-metric-definitions sales.*), kept per (branch, purpose). Every bundle of a
 * response — totals, byPurpose, a branch row — is a sum of these cells, so the numbers of one response always agree.
 * Arithmetic is exact: an overflow fails rather than wraps (amounts are won integers).
 */
final class RevenueTally {
    static final String HAS_DATA = "hasData";
    static final String NO_DATA = "noData";

    /** One cell or a sum of cells. approvedCount + refundCount is the number of source rows behind it. */
    record Amounts(long approvedAmount, long refundAmount, long approvedCount, long refundCount) {
        static final Amounts ZERO = new Amounts(0, 0, 0, 0);

        Amounts plus(Amounts other) {
            return new Amounts(Math.addExact(approvedAmount, other.approvedAmount), Math.addExact(refundAmount, other.refundAmount),
                    Math.addExact(approvedCount, other.approvedCount), Math.addExact(refundCount, other.refundCount));
        }

        long net() { return Math.subtractExact(approvedAmount, refundAmount); }
        long sourceRows() { return Math.addExact(approvedCount, refundCount); }
        String dataStatus() { return sourceRows() == 0 ? NO_DATA : HAS_DATA; }

        RevenueAmounts view() {
            return new RevenueAmounts(approvedAmount, refundAmount, net(), approvedCount, refundCount, dataStatus());
        }

        PurposeRevenue view(SalesPurpose purpose) {
            return new PurposeRevenue(purpose.label, approvedAmount, refundAmount, net(), approvedCount, refundCount, dataStatus());
        }
    }

    private final Map<Long, Map<SalesPurpose, Amounts>> cells = new HashMap<>();

    RevenueTally(List<SourceTotal> totals) {
        for (var total : totals) {
            var cell = total.type() == LedgerEntryType.APPROVAL
                    ? new Amounts(total.amountKrw(), 0, total.count(), 0)
                    : new Amounts(0, total.amountKrw(), 0, total.count());
            cells.computeIfAbsent(total.branchId(), id -> new EnumMap<>(SalesPurpose.class)).merge(total.purpose(), cell, Amounts::plus);
        }
    }

    Amounts of(long branchId, SalesPurpose purpose) {
        return cells.getOrDefault(branchId, Map.of()).getOrDefault(purpose, Amounts.ZERO);
    }

    Amounts of(List<Long> branchIds, List<SalesPurpose> purposes) {
        var sum = Amounts.ZERO;
        for (long branch : branchIds) for (var purpose : purposes) sum = sum.plus(of(branch, purpose));
        return sum;
    }

    /** The byPurpose rows in the contract order 이용권 → 스터디룸 → 사물함, zero rows included. */
    List<PurposeRevenue> byPurpose(List<Long> branchIds, List<SalesPurpose> purposes) {
        var rows = new ArrayList<PurposeRevenue>();
        for (var purpose : purposes) rows.add(of(branchIds, List.of(purpose)).view(purpose));
        return rows;
    }
}
