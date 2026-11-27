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

    private final AggregationGranularity granularity;
    private final List<Bucket> buckets = new ArrayList<>();
    private final Map<LocalDate, Integer> positions = new HashMap<>();

    record Bucket(
            LocalDate key,
            String label,
            Instant bucketStart,
            Instant bucketEnd,
            Instant start,
            Instant end
    ) {

        boolean partial() {
            boolean cutAtStart = !start.equals(bucketStart);
            boolean cutAtEnd = !end.equals(bucketEnd);

            return cutAtStart || cutAtEnd;
        }
    }

    TrendBuckets(Instant from, Instant to, AggregationGranularity granularity) {
        this.granularity = granularity;
        LocalDate key = toKey(LocalDate.ofInstant(from, AggregationConditions.SEOUL));
        Instant bucketStart = toStart(key);

        while (bucketStart.isBefore(to)) {
            LocalDate nextKey = toNextKey(key);
            Instant bucketEnd = toStart(nextKey);
            positions.put(key, buckets.size());
            buckets.add(new Bucket(
                    key,
                    toLabel(key),
                    bucketStart,
                    bucketEnd,
                    max(bucketStart, from),
                    min(bucketEnd, to)
            ));
            key = nextKey;
            bucketStart = bucketEnd;
        }
    }

    List<Bucket> buckets() {
        return List.copyOf(buckets);
    }

    /** The position of the bucket holding an Asia/Seoul day of a source row inside the period. */
    int findPosition(LocalDate day) {
        Integer position = positions.get(toKey(day));

        if (position == null) {
            throw new IllegalStateException("A source day " + day + " lies outside the trend period");
        }

        return position;
    }

    private LocalDate toKey(LocalDate day) {
        return switch (granularity) {
            case DAY -> day;
            case WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> day.withDayOfMonth(1);
        };
    }

    private LocalDate toNextKey(LocalDate key) {
        return switch (granularity) {
            case DAY -> key.plusDays(1);
            case WEEK -> key.plusWeeks(1);
            case MONTH -> key.plusMonths(1);
        };
    }

    /** day: YYYY-MM-DD, week: its Monday YYYY-MM-DD, month: YYYY-MM, all Asia/Seoul. */
    private String toLabel(LocalDate key) {
        return granularity == AggregationGranularity.MONTH ? YearMonth.from(key).toString() : key.toString();
    }

    private static Instant toStart(LocalDate day) {
        return day.atStartOfDay(AggregationConditions.SEOUL).toInstant();
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
