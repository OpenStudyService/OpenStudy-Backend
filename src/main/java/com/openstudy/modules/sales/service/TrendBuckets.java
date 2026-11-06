package com.openstudy.modules.sales.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.openstudy.modules.sales.dto.request.AggregationGranularity;

/**
 * The buckets of a trend (M9-D04): every day, week (Monday 00:00) or month (1st 00:00) in Asia/Seoul that overlaps
 * [from, to), ascending, empty ones included. A bucket cut by the period keeps its whole bounds in bucketStart/bucketEnd
 * and the counted part in start/end. Source rows arrive grouped by Asia/Seoul day; a day belongs to exactly one bucket
 * because every bucket boundary is a Seoul midnight, so each source row lands in one bucket only.
 */
final class TrendBuckets {
    record Bucket(LocalDate key, String label, Instant bucketStart, Instant bucketEnd, Instant start, Instant end) {
        boolean partial() { return !start.equals(bucketStart) || !end.equals(bucketEnd); }
    }

    private final AggregationGranularity granularity;
    private final List<Bucket> buckets = new ArrayList<>();
    private final Map<LocalDate, Integer> index = new HashMap<>();

    TrendBuckets(Instant from, Instant to, AggregationGranularity granularity) {
        this.granularity = granularity;
        LocalDate key = key(LocalDate.ofInstant(from, AggregationConditions.SEOUL));
        Instant bucketStart = start(key);
        while (bucketStart.isBefore(to)) {
            LocalDate next = next(key);
            Instant bucketEnd = start(next);
            index.put(key, buckets.size());
            buckets.add(new Bucket(key, label(key), bucketStart, bucketEnd, max(bucketStart, from), min(bucketEnd, to)));
            key = next;
            bucketStart = bucketEnd;
        }
    }

    List<Bucket> buckets() { return List.copyOf(buckets); }

    /** The position of the bucket holding an Asia/Seoul day of a source row inside the period. */
    int of(LocalDate day) {
        Integer position = index.get(key(day));
        if (position == null) throw new IllegalStateException("A source day " + day + " lies outside the trend period");
        return position;
    }

    private LocalDate key(LocalDate day) {
        return switch (granularity) {
            case DAY -> day;
            case WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> day.withDayOfMonth(1);
        };
    }

    private LocalDate next(LocalDate key) {
        return switch (granularity) {
            case DAY -> key.plusDays(1);
            case WEEK -> key.plusWeeks(1);
            case MONTH -> key.plusMonths(1);
        };
    }

    /** day: YYYY-MM-DD, week: its Monday YYYY-MM-DD, month: YYYY-MM, all Asia/Seoul. */
    private String label(LocalDate key) {
        return granularity == AggregationGranularity.MONTH ? YearMonth.from(key).toString() : key.toString();
    }

    private static Instant start(LocalDate day) { return day.atStartOfDay(AggregationConditions.SEOUL).toInstant(); }
    private static Instant max(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
    private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
}
