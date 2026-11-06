package com.openstudy.modules.sales.dto.response;

import java.util.List;

/**
 * Response contract for M9 {@code GET /api/sales} ({@code OS-DL-0402}, {@code ADR-M9-001}).
 * <p>
 * {@code rows} matches the frontend {@code SalesRow} contract in {@code Sales.tsx} / {@code Charts.tsx}
 * ({@code branchId}, {@code branch}, {@code approved}, {@code refunds}, {@code net}) while also exposing
 * exact counts and period metadata shared with M10 DomainLens sales analyses.
 */
public record SalesResponse(
        List<BranchRow> rows,
        Totals totals,
        String refundBasis,
        String attribution,
        String startDateKst,
        String endDateKst
) {
    public SalesResponse {
        rows = List.copyOf(rows);
    }

    public record BranchRow(
            String branchId,
            String branch,
            long approved,
            long refunds,
            long net,
            long approvedCount,
            long refundCount
    ) {}

    public record Totals(
            long approvedKrw,
            long refundKrw,
            long netKrw,
            long approvedCount,
            long refundCount
    ) {}
}
