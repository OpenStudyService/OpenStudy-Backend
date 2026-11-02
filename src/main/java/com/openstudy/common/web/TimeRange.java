package com.openstudy.common.web;

import java.time.DateTimeException;
import java.time.Instant;

import com.openstudy.common.exception.ApiException;

/** An optional [from, to) filter in ISO 8601 UTC (api.md). Either end may be missing; both given need from &lt; to. */
public record TimeRange(Instant from, Instant to) {
    /** What DATETIME(6) can hold; a syntactically valid year beyond it must stay a 422, not a failed bind. */
    private static final Instant EARLIEST = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-01-01T00:00:00Z");

    public static TimeRange parse(String from, String to) {
        Instant start = instant(from, "from");
        Instant end = instant(to, "to");
        if (start != null && end != null && !start.isBefore(end)) throw ApiException.validation("to", "종료 시각은 시작 시각보다 뒤여야 합니다.");
        return new TimeRange(start, end);
    }

    /** One timestamp field of a filter or a command; null stays null. */
    public static Instant instant(String value, String field) {
        if (value == null) return null;
        try {
            Instant parsed = Instant.parse(value);
            if (!parsed.isBefore(EARLIEST) && parsed.isBefore(LATEST)) return parsed;
        } catch (DateTimeException exception) {
            // Reported below, together with an out-of-range year.
        }
        throw ApiException.validation(field, "ISO 8601 UTC 시각을 입력해 주세요.");
    }

    public boolean contains(Instant at) {
        return (from == null || !at.isBefore(from)) && (to == null || at.isBefore(to));
    }
}
