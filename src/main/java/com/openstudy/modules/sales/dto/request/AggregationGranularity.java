package com.openstudy.modules.sales.dto.request;

import java.util.Arrays;

/** Trend bucket (M9-D04, #88). Only a trend accepts it; every other aggregation refuses a value. */
public enum AggregationGranularity {
    DAY("day"),
    WEEK("week"),
    MONTH("month");

    public final String wire;
    AggregationGranularity(String wire) { this.wire = wire; }

    public static AggregationGranularity fromWire(String value) {
        return Arrays.stream(values()).filter(granularity -> granularity.wire.equals(value)).findFirst().orElse(null);
    }
}
