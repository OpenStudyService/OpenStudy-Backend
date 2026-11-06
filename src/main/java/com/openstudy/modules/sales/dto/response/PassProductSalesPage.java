package com.openstudy.modules.sales.dto.response;

import java.util.List;
import com.openstudy.common.web.PageResponse;

/**
 * Contract schema {@code PassProductSalesPage}. totals cover every row of the condition, not the page, and equal the
 * GET /sales/passes/volume totals of the same condition; meta.total = aggregation.rowCount.
 */
public record PassProductSalesPage(List<PassProductSales> items, PageResponse.Meta meta, AggregationMeta aggregation,
        PassVolume totals) {
    public PassProductSalesPage { items = List.copyOf(items); }
}
