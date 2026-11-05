package com.openstudy.modules.sales.dto.response;

import java.util.List;
import com.openstudy.common.web.PageResponse;

/**
 * Contract schema {@code SalesLedgerPage}. totals cover every item of the condition, not the page, and equal the
 * revenue totals of the same condition; meta.total = aggregation.sourceRowCount.
 */
public record SalesLedgerPage(List<SalesLedgerEntry> items, PageResponse.Meta meta, AggregationMeta aggregation,
        RevenueAmounts totals) {
    public SalesLedgerPage { items = List.copyOf(items); }
}
