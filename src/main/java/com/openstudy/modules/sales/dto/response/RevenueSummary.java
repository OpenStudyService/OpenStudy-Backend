package com.openstudy.modules.sales.dto.response;

import java.util.List;

/** Contract schema {@code RevenueSummary}: totals = the sum of byPurpose (FR-AGG-01·17). */
public record RevenueSummary(AggregationMeta aggregation, RevenueAmounts totals, List<PurposeRevenue> byPurpose) {
    public RevenueSummary { byPurpose = List.copyOf(byPurpose); }
}
