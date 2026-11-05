package com.openstudy.modules.sales.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Contract schema {@code AggregationMeta} (FR-AGG-09), the same shape on every sales aggregation. dataStatus is noData
 * exactly when sourceRowCount is 0; sourceRowCount equals the ledger total of the same condition.
 */
public record AggregationMeta(AggregationConditionView condition, boolean defaultPeriod, String timeZone, String currency,
        Instant asOf, long rowCount, long sourceRowCount, String dataStatus, AggregationPartial partial,
        List<MetricRef> metrics) {
    public AggregationMeta { metrics = List.copyOf(metrics); }
}
