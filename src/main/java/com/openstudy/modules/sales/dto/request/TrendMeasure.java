package com.openstudy.modules.sales.dto.request;

import java.util.Arrays;

/**
 * What a trend counts (aggregation-api.md "추세"): revenue amounts or pass quantities, never both in one response, so a
 * filter and sourceRowCount always refer to one kind of number. revenue takes purpose, passes takes productId·categoryCode.
 */
public enum TrendMeasure {
    REVENUE("revenue"),
    PASSES("passes");

    public final String wire;
    TrendMeasure(String wire) { this.wire = wire; }

    public static TrendMeasure fromWire(String value) {
        return Arrays.stream(values()).filter(measure -> measure.wire.equals(value)).findFirst().orElse(null);
    }
}
