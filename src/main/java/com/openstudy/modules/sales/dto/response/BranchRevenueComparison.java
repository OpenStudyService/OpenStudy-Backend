package com.openstudy.modules.sales.dto.response;

import java.util.List;

/** Contract schema {@code BranchRevenueComparison} (FR-AGG-02): totals = the sum of items = GET /sales/revenue totals. */
public record BranchRevenueComparison(AggregationMeta aggregation, RevenueAmounts totals, List<BranchRevenue> items) {
    public BranchRevenueComparison { items = List.copyOf(items); }
}
