package com.openstudy.modules.sales.dto.response;

import java.time.Instant;

/**
 * Contract schema {@code SalesTrendBucket}. bucketStart/bucketEnd are the whole bucket, start/end the part inside the
 * period; partial when they differ. Exactly one of revenue and passes is set, following the trend's measure.
 */
public record SalesTrendBucket(String label, Instant bucketStart, Instant bucketEnd, Instant start, Instant end,
        boolean partial, RevenueAmounts revenue, PassVolume passes) {}
