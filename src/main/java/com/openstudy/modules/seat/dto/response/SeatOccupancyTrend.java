package com.openstudy.modules.seat.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Contract schema {@code SeatOccupancyTrend}: occupied seat-minutes ÷ (seats × bucket minutes) per hour or day (M13-D09). */
public record SeatOccupancyTrend(String branchId, String granularity, Instant from, Instant to, Instant asOf, String timeZone,
        Totals totals, List<Bucket> items) {
    public SeatOccupancyTrend { items = List.copyOf(items); }

    /** Contract schema {@code SeatOccupancyBucket}: end = min(bucketEnd, to, asOf). */
    public record Bucket(String label, Instant bucketStart, Instant bucketEnd, Instant start, Instant end, boolean partial, long seatCount,
            long occupiedSeatMinutes, long capacitySeatMinutes, Ratio occupancy) {}

    public record Totals(long occupiedSeatMinutes, long capacitySeatMinutes, Ratio occupancy) {}

    /** Contract schema {@code Ratio}. */
    public record Ratio(String state, BigDecimal percent, long numerator, long denominator) {}
}
