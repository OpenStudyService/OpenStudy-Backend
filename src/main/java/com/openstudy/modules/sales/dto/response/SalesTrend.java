package com.openstudy.modules.sales.dto.response;

import java.util.List;

/** Contract schema {@code SalesTrend} (FR-AGG-14): every bucket of the period, Σ buckets = totals. */
public record SalesTrend(AggregationMeta aggregation, String measure, String granularity, SalesTrendTotals totals,
        List<SalesTrendBucket> items) {
    public SalesTrend { items = List.copyOf(items); }
}
