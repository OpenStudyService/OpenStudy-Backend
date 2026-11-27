package com.openstudy.modules.seat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.seat.dto.response.SeatOccupancyTrend;
import com.openstudy.modules.seat.repository.SeatOccupancyRepository;

/**
 * GET /seats/occupancy-trend (operations-extension-api.md "좌석 점유 추이", M13-D09): for every hour or day of the period,
 * occupied seat-minutes ÷ (seats × bucket minutes). A stay occupies [effective check-in, effective check-out or asOf); the
 * seats of a bucket are those registered before its end (no stop history exists to remove blocked ones). Everything is
 * computed from seat_sessions and seats, so every bucket can be recomputed from them.
 */
@Service
@RequiredArgsConstructor
public class SeatOccupancyTrendService {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Duration MAX_HOURLY = Duration.ofDays(31);
    private static final Duration MAX_DAILY = Duration.ofDays(366);
    private static final DateTimeFormatter HOUR_LABEL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");
    private static final String ID_FORMAT = "[1-9][0-9]{0,18}";
    private static final String HOUR = "hour";
    private static final String DAY = "day";
    private static final long SECONDS_PER_MINUTE = 60;
    private static final int PERCENT_SCALE = 1;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final AuthorizationService authorizationService;
    private final SeatOccupancyRepository seatOccupancyRepository;
    private final Clock clock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SeatOccupancyTrend trend(long actor, String branchId, String from, String to, String granularity) {
        if (branchId == null) {
            throw ApiException.validation("branchId", "지점을 선택해 주세요.");
        }

        if (!branchId.matches(ID_FORMAT)) {
            throw ApiException.validation("branchId", "올바른 ID를 입력해 주세요.");
        }

        long branch = Long.parseLong(branchId);
        boolean hourly = granularity == null || HOUR.equals(granularity);

        if (!hourly && !DAY.equals(granularity)) {
            throw ApiException.validation("granularity", "허용되지 않은 값입니다.");
        }

        if ((from == null) != (to == null)) {
            throw ApiException.validation(from == null ? "from" : "to", "시작 시각과 종료 시각을 함께 입력해 주세요.");
        }

        Instant start = parseInstant(from, "from");
        Instant end = parseInstant(to, "to");
        AuthorizationState state = authorizationService.current(actor);
        authorizationService.require(state, Set.of(branch), BranchAction.READ);
        Instant asOf = clock.instant().truncatedTo(ChronoUnit.MICROS);

        if (start == null) {
            LocalDate today = LocalDate.ofInstant(asOf, SEOUL);
            start = today.atStartOfDay(SEOUL).toInstant();
            end = today.plusDays(1).atStartOfDay(SEOUL).toInstant();
        }

        if (!start.isBefore(end)) {
            throw ApiException.validation("to", "종료 시각은 시작 시각보다 뒤여야 합니다.");
        }

        if (Duration.between(start, end).compareTo(hourly ? MAX_HOURLY : MAX_DAILY) > 0) {
            String message = hourly ? "시간 단위는 31일까지입니다." : "일 단위는 366일까지입니다.";
            throw ApiException.invalid("PERIOD_TOO_LONG", message, "to", "기간을 줄여 주세요.");
        }

        List<Instant> seatCreated = seatOccupancyRepository.findSeatRegistrations(branch);
        List<Instant[]> stays = new ArrayList<>();

        for (Instant[] stay : seatOccupancyRepository.findStays(branch, start, end)) {
            stays.add(new Instant[]{stay[0], stay[1] == null ? asOf : stay[1]});
        }

        List<SeatOccupancyTrend.Bucket> items = new ArrayList<>();
        long occupiedTotal = 0;
        long capacityTotal = 0;
        ZonedDateTime cursor = floor(start, hourly);

        while (cursor.toInstant().isBefore(end)) {
            ZonedDateTime next = hourly ? cursor.plusHours(1) : cursor.plusDays(1);
            SeatOccupancyTrend.Bucket bucket = toBucket(cursor, next, hourly, start, end, asOf, seatCreated, stays);
            occupiedTotal += bucket.occupiedSeatMinutes();
            capacityTotal += bucket.capacitySeatMinutes();
            items.add(bucket);
            cursor = next;
        }

        SeatOccupancyTrend.Totals totals = new SeatOccupancyTrend.Totals(
                occupiedTotal,
                capacityTotal,
                toRatio(occupiedTotal, capacityTotal)
        );

        return new SeatOccupancyTrend(
                Long.toString(branch),
                hourly ? HOUR : DAY,
                start,
                end,
                asOf,
                SEOUL.getId(),
                totals,
                items
        );
    }

    /** Contract schema {@code Ratio}: one decimal, HALF_UP; a zero denominator is "해당 없음" (M9-D09). */
    static SeatOccupancyTrend.Ratio toRatio(long numerator, long denominator) {
        if (denominator == 0) {
            return new SeatOccupancyTrend.Ratio("notApplicable", null, numerator, 0);
        }

        BigDecimal percent = BigDecimal.valueOf(numerator)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(denominator), PERCENT_SCALE, RoundingMode.HALF_UP);

        return new SeatOccupancyTrend.Ratio("value", percent, numerator, denominator);
    }

    /** One hour or day, cut to [start, end) and to asOf; a bucket wholly after asOf has no minutes. */
    private static SeatOccupancyTrend.Bucket toBucket(
            ZonedDateTime cursor,
            ZonedDateTime next,
            boolean hourly,
            Instant start,
            Instant end,
            Instant asOf,
            List<Instant> seatCreated,
            List<Instant[]> stays
    ) {
        Instant bucketStart = cursor.toInstant();
        Instant bucketEnd = next.toInstant();
        Instant shownStart = max(bucketStart, start);
        Instant cutEnd = min(min(bucketEnd, end), asOf);
        Instant shownEnd = shownStart.isBefore(cutEnd) ? cutEnd : shownStart;
        long seats = seatCreated.stream()
                .filter(created -> created.isBefore(shownEnd))
                .count();
        long occupied = 0;
        long capacity = 0;

        if (shownStart.isBefore(cutEnd)) {
            occupied = countOccupiedSeconds(stays, shownStart, cutEnd) / SECONDS_PER_MINUTE;
            capacity = seats * Duration.between(shownStart, cutEnd).toMinutes();
        }

        String label = hourly ? HOUR_LABEL.format(cursor) : cursor.toLocalDate().toString();
        boolean partial = !shownStart.equals(bucketStart) || !shownEnd.equals(bucketEnd);

        return new SeatOccupancyTrend.Bucket(
                label,
                bucketStart,
                bucketEnd,
                shownStart,
                shownEnd,
                partial,
                seats,
                occupied,
                capacity,
                toRatio(occupied, capacity)
        );
    }

    private static long countOccupiedSeconds(List<Instant[]> stays, Instant from, Instant to) {
        long seconds = 0;

        for (Instant[] stay : stays) {
            Instant overlapStart = max(stay[0], from);
            Instant overlapEnd = min(stay[1], to);

            if (overlapStart.isBefore(overlapEnd)) {
                seconds += Duration.between(overlapStart, overlapEnd).toSeconds();
            }
        }

        return seconds;
    }

    private static ZonedDateTime floor(Instant at, boolean hourly) {
        ZonedDateTime local = at.atZone(SEOUL);

        return hourly ? local.truncatedTo(ChronoUnit.HOURS) : local.toLocalDate().atStartOfDay(SEOUL);
    }

    private static Instant parseInstant(String value, String field) {
        if (value == null) {
            return null;
        }

        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeException exception) {
            throw ApiException.validation(field, "시간대 오프셋이 있는 ISO 8601 시각을 입력해 주세요.");
        }
    }

    private static Instant max(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static Instant min(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }
}
