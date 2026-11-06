package com.openstudy.domainlens.query;

import com.openstudy.domainlens.api.error.EngineErrorCode;
import com.openstudy.domainlens.api.error.EngineException;
import com.openstudy.domainlens.api.ir.Filter;
import com.openstudy.domainlens.api.spi.ClockPort;
import java.time.*;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Half-open intervals in the consumer's explicit calendar zone and valid data range.
 * Out-of-range requests fail with CLOCK_OUT_OF_RANGE, without clipping. A conversation
 * layer may turn that failure into NEEDS_CLARIFICATION; normalization never executes it.
 */
public final class DateNormalizer {
    public record Range(Instant startInclusive, Instant endExclusive) {
        public Range {
            argument(startInclusive != null && endExclusive != null, "Date range endpoints required");
            argument(startInclusive.isBefore(endExclusive), "Empty or reversed date range");
        }
    }

    private final ClockPort clock;
    private final ZoneId calendarZone;
    private final Range valid;

    public DateNormalizer(@Nullable ClockPort clock, ZoneId calendarZone, Range valid) {
        requireAsOf(clock);
        argument(calendarZone != null && valid != null, "Calendar zone and valid range required");
        this.clock = clock;
        this.calendarZone = calendarZone;
        this.valid = valid;
    }

    public ZoneId calendarZone() { return calendarZone; }

    public Range month(YearMonth month) {
        argument(month != null, "Month required");
        return checked(new Range(month.atDay(1).atStartOfDay(calendarZone).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(calendarZone).toInstant()));
    }

    public Range year(int year) { return checked(yearRange(year)); }

    /** A reference guard only: overlap does not prove a complete requested interval is valid. */
    public void checkedYearReference(int year) {
        var range=yearRange(year);
        if(!range.startInclusive().isBefore(valid.endExclusive()) || !range.endExclusive().isAfter(valid.startInclusive()))
            throw new EngineException(EngineErrorCode.CLOCK_OUT_OF_RANGE,"Referenced year outside valid data range");
    }
    private Range yearRange(int year) {
        return new Range(LocalDate.of(year, 1, 1).atStartOfDay(calendarZone).toInstant(),
                LocalDate.of(year, 1, 1).plusYears(1).atStartOfDay(calendarZone).toInstant());
    }

    public Range relativeMonth(int offset) {
        Instant asOf = requireAsOf(clock);
        return month(YearMonth.from(asOf.atZone(calendarZone)).plusMonths(offset));
    }

    /**
     * Checks an interval endpoint, allowing the valid range's exclusive end as an
     * upper bound. This is not a data-membership check: use LT for that upper bound.
     */
    public void checkedBoundary(Instant instant) {
        argument(instant != null, "Date boundary required");
        if (instant.isBefore(valid.startInclusive()) || instant.isAfter(valid.endExclusive()))
            throw new EngineException(EngineErrorCode.CLOCK_OUT_OF_RANGE, "Date boundary outside valid data range");
    }

    public Range checked(Range range) {
        argument(range != null, "Date range required");
        if (range.startInclusive().isBefore(valid.startInclusive()) || range.endExclusive().isAfter(valid.endExclusive()))
            throw new EngineException(EngineErrorCode.CLOCK_OUT_OF_RANGE, "Date range requires clarification or correction");
        return range;
    }

    /**
     * Produces a timestamp filter with UTC Instant strings and GTE/LT endpoints.
     * The caller must select a registered timestamp field and pass the query through
     * IrValidator for field types, allowed operators, and policy validation before execution.
     */
    public Filter.Group toFilter(String fieldId, Range range) {
        argument(fieldId != null && !fieldId.isBlank(), "Field ID required");
        checked(range);
        return new Filter.Group(Filter.Junction.AND, List.of(
                new Filter.Leaf(fieldId, Filter.Operator.GTE, List.of(range.startInclusive().toString())),
                new Filter.Leaf(fieldId, Filter.Operator.LT, List.of(range.endExclusive().toString()))));
    }

    private static Instant requireAsOf(@Nullable ClockPort clock) {
        Instant asOf = clock == null ? null : clock.asOf();
        if (asOf == null) throw new EngineException(EngineErrorCode.NOT_READY, "Clock/asOf required");
        return asOf;
    }

    private static void argument(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
