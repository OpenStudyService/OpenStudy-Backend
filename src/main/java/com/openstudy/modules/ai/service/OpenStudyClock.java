package com.openstudy.modules.ai.service;

import com.openstudy.domainlens.api.spi.ClockPort;
import com.openstudy.domainlens.query.DateNormalizer;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * OpenStudy reference clock and KST ({@code Asia/Seoul}) calendar period resolver ({@code OS-DL-0401}).
 * <p>
 * The reference time is the real current time (ADR-M10-003, 2026-11-08). A fixed time is used only when
 * {@code openstudy.ai.fixed-as-of} is set, which the recorded playback checks and tests do
 * ({@code 2026-11-02T05:00:00Z} reproduces the PoC fixtures).
 * All calendar day/week/month boundaries are computed in {@code Asia/Seoul} and converted to UTC
 * {@link Instant} half-open intervals {@code [startInclusive, endExclusive)} for database queries.
 */
@Service
public class OpenStudyClock implements ClockPort {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public static final Instant DEFAULT_AS_OF = Instant.parse("2026-11-02T05:00:00Z");
    public static final DateNormalizer.Range VALID_DATA_RANGE = new DateNormalizer.Range(
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2027-01-01T00:00:00Z")
    );

    private final Instant fixedAsOf;
    private final boolean useSystemClock;

    public OpenStudyClock(
            @Value("${openstudy.ai.fixed-as-of:}") String fixedAsOfText,
            @Value("${openstudy.ai.use-system-clock:false}") boolean useSystemClock
    ) {
        boolean fixed = fixedAsOfText != null && !fixedAsOfText.isBlank();
        this.fixedAsOf = fixed ? parseInstantOrOffset(fixedAsOfText.trim()) : DEFAULT_AS_OF;
        this.useSystemClock = useSystemClock || !fixed;
    }

    public static OpenStudyClock fixed(Instant asOf) {
        return new OpenStudyClock(asOf.toString(), false);
    }

    @Override
    public @NonNull Instant asOf() {
        return useSystemClock ? Instant.now() : fixedAsOf;
    }

    @Override
    public @NonNull ZoneId displayZone() {
        return KST;
    }

    public ZonedDateTime asOfKst() {
        return asOf().atZone(KST);
    }

    public OffsetDateTime asOfOffsetKst() {
        return asOfKst().toOffsetDateTime();
    }

    public LocalDate todayDateKst() {
        return asOfKst().toLocalDate();
    }

    public DateNormalizer normalizer() {
        return new DateNormalizer(this, KST, VALID_DATA_RANGE);
    }

    /**
     * Half-open KST period with both UTC {@link Instant} boundaries and {@code +09:00} {@link OffsetDateTime} views.
     */
    public record KstPeriod(
            String code,
            Instant startInclusive,
            Instant endExclusive,
            OffsetDateTime startKst,
            OffsetDateTime endExclusiveKst
    ) {
        public DateNormalizer.Range toRange() {
            return new DateNormalizer.Range(startInclusive, endExclusive);
        }

        public LocalDate startDateKst() {
            return startKst.toLocalDate();
        }

        public LocalDate endDateExclusiveKst() {
            return endExclusiveKst.toLocalDate();
        }
    }

    /**
     * {@code THIS_MONTH} ("이번 달") for PoC-001: {@code [2026-11-01T00:00:00+09:00, 2026-11-03T00:00:00+09:00)}.
     * Includes all calendar days from the 1st of the current KST month through the end of the {@code asOf} KST day.
     */
    public KstPeriod thisMonth() {
        LocalDate today = todayDateKst();
        LocalDate startDay = YearMonth.from(today).atDay(1);
        LocalDate endDayExclusive = today.plusDays(1);
        return fromDates("THIS_MONTH", startDay, endDayExclusive);
    }

    /**
     * Full calendar month for {@code asOf} in KST: {@code [2026-11-01T00:00:00+09:00, 2026-12-01T00:00:00+09:00)}.
     */
    public KstPeriod thisFullCalendarMonth() {
        YearMonth ym = YearMonth.from(todayDateKst());
        return fromDates("THIS_FULL_MONTH", ym.atDay(1), ym.plusMonths(1).atDay(1));
    }

    /**
     * {@code LAST_MONTH} ("지난달"): {@code [2026-10-01T00:00:00+09:00, 2026-11-01T00:00:00+09:00)}.
     */
    public KstPeriod lastMonth() {
        YearMonth prev = YearMonth.from(todayDateKst()).minusMonths(1);
        return fromDates("LAST_MONTH", prev.atDay(1), prev.plusMonths(1).atDay(1));
    }

    /**
     * {@code THIS_WEEK} ("이번 주", Monday 00:00 KST to next Monday 00:00 KST):
     * {@code [2026-11-02T00:00:00+09:00, 2026-11-09T00:00:00+09:00)}.
     */
    public KstPeriod thisWeek() {
        LocalDate monday = todayDateKst().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return fromDates("THIS_WEEK", monday, monday.plusWeeks(1));
    }

    /**
     * {@code LAST_WEEK} ("지난주", previous Monday 00:00 KST to current Monday 00:00 KST):
     * {@code [2026-10-26T00:00:00+09:00, 2026-11-02T00:00:00+09:00)}.
     */
    public KstPeriod lastWeek() {
        LocalDate currentMonday = todayDateKst().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return fromDates("LAST_WEEK", currentMonday.minusWeeks(1), currentMonday);
    }

    /**
     * {@code PREVIOUS_TWO_WEEKS} (used by PoC-002 seat congestion):
     * {@code [2026-10-19T00:00:00+09:00, 2026-11-02T00:00:00+09:00)}.
     */
    public KstPeriod previousTwoWeeks() {
        LocalDate currentMonday = todayDateKst().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return fromDates("PREVIOUS_TWO_WEEKS", currentMonday.minusWeeks(2), currentMonday);
    }

    /**
     * {@code NEXT_7_DAYS} ("앞으로 7일 이내", {@code [asOf, asOf + 7 days)}):
     * {@code [2026-11-02T14:00:00+09:00, 2026-11-09T14:00:00+09:00)}.
     */
    public KstPeriod next7Days() {
        Instant start = asOf();
        Instant end = start.plus(Duration.ofDays(7));
        return new KstPeriod(
                "NEXT_7_DAYS",
                start,
                end,
                start.atZone(KST).toOffsetDateTime(),
                end.atZone(KST).toOffsetDateTime()
        );
    }

    /**
     * {@code TODAY} ("오늘" / "지금"): {@code [2026-11-02T00:00:00+09:00, 2026-11-03T00:00:00+09:00)}.
     */
    public KstPeriod today() {
        LocalDate today = todayDateKst();
        return fromDates("TODAY", today, today.plusDays(1));
    }

    public KstPeriod fromDates(String code, LocalDate startInclusiveKst, LocalDate endExclusiveKst) {
        ZonedDateTime startZdt = startInclusiveKst.atStartOfDay(KST);
        ZonedDateTime endZdt = endExclusiveKst.atStartOfDay(KST);
        return new KstPeriod(
                code,
                startZdt.toInstant(),
                endZdt.toInstant(),
                startZdt.toOffsetDateTime(),
                endZdt.toOffsetDateTime()
        );
    }

    public KstPeriod resolvePeriod(String periodCode) {
        if (periodCode == null || periodCode.isBlank()) {
            return thisMonth();
        }
        return switch (periodCode.trim().toUpperCase(Locale.ROOT)) {
            case "THIS_MONTH", "이번 달", "이번달" -> thisMonth();
            case "THIS_FULL_MONTH" -> thisFullCalendarMonth();
            case "LAST_MONTH", "지난달", "지난 달" -> lastMonth();
            case "THIS_WEEK", "이번 주", "이번주" -> thisWeek();
            case "LAST_WEEK", "지난주", "지난 주" -> lastWeek();
            case "PREVIOUS_TWO_WEEKS" -> previousTwoWeeks();
            case "NEXT_7_DAYS", "앞으로 7일 이내", "7일 이내" -> next7Days();
            case "TODAY", "오늘", "지금" -> today();
            default -> throw new IllegalArgumentException("Unsupported relative period: " + periodCode);
        };
    }

    private static Instant parseInstantOrOffset(String value) {
        if (value.endsWith("Z") || value.endsWith("z")) {
            return Instant.parse(value);
        }
        return OffsetDateTime.parse(value).toInstant();
    }
}
