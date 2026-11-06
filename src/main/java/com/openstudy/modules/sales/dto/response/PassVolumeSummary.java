package com.openstudy.modules.sales.dto.response;

/** Contract schema {@code PassVolumeSummary} (FR-PASS-05, FR-AGG-03). */
public record PassVolumeSummary(AggregationMeta aggregation, PassVolume totals) {}
