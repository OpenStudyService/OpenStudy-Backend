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

    private final AuthorizationService policy;
    private final SeatOccupancyRepository occupancy;
    private final Clock clock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SeatOccupancyTrend trend(long actor, String branchId, String from, String to, String granularity) {
        if (branchId == null) throw ApiException.validation("branchId", "지점을 선택해 주세요.");
        if (!branchId.matches("[1-9][0-9]{0,18}")) throw ApiException.validation("branchId", "올바른 ID를 입력해 주세요.");
        long branch = Long.parseLong(branchId);
        boolean hourly = granularity == null || "hour".equals(granularity);
        if (!hourly && !"day".equals(granularity)) throw ApiException.validation("granularity", "허용되지 않은 값입니다.");
        if ((from == null) != (to == null)) throw ApiException.validation(from == null ? "from" : "to", "시작 시각과 종료 시각을 함께 입력해 주세요.");
        Instant start = instant(from, "from"), end = instant(to, "to");
        var state = policy.current(actor);
        policy.require(state, Set.of(branch), BranchAction.READ);
        Instant asOf = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (start == null) {
            LocalDate today = LocalDate.ofInstant(asOf, SEOUL);
            start = today.atStartOfDay(SEOUL).toInstant();
            end = today.plusDays(1).atStartOfDay(SEOUL).toInstant();
        }
        if (!start.isBefore(end)) throw ApiException.validation("to", "종료 시각은 시작 시각보다 뒤여야 합니다.");
        if (Duration.between(start, end).compareTo(hourly ? MAX_HOURLY : MAX_DAILY) > 0)
            throw ApiException.invalid("PERIOD_TOO_LONG", hourly ? "시간 단위는 31일까지입니다." : "일 단위는 366일까지입니다.", "to", "기간을 줄여 주세요.");

        var seatCreated = occupancy.seatsRegistered(branch);
        var stays = new ArrayList<Instant[]>();
        for (var stay : occupancy.stays(branch, start, end)) stays.add(new Instant[]{stay[0], stay[1] == null ? asOf : stay[1]});

        var items = new ArrayList<SeatOccupancyTrend.Bucket>();
        long occupiedTotal = 0, capacityTotal = 0;
        ZonedDateTime cursor = floor(start, hourly);
        while (cursor.toInstant().isBefore(end)) {
            ZonedDateTime next = hourly ? cursor.plusHours(1) : cursor.plusDays(1);
            Instant bucketStart = cursor.toInstant(), bucketEnd = next.toInstant();
            Instant from2 = max(bucketStart, start);
            Instant to2 = min(min(bucketEnd, end), asOf);
            Instant shownEnd = from2.isBefore(to2) ? to2 : from2;
            long seats = seatCreated.stream().filter(created -> created.isBefore(shownEnd)).count();
            long occupied = 0, capacity = 0;
            if (from2.isBefore(to2)) {
                long seconds = 0;
                for (var stay : stays) {
                    Instant a = max(stay[0], from2), b = min(stay[1], to2);
                    if (a.isBefore(b)) seconds += Duration.between(a, b).toSeconds();
                }
                occupied = seconds / 60;
                capacity = seats * Duration.between(from2, to2).toMinutes();
            }
            occupiedTotal += occupied;
            capacityTotal += capacity;
            items.add(new SeatOccupancyTrend.Bucket(hourly ? HOUR_LABEL.format(cursor) : cursor.toLocalDate().toString(), bucketStart, bucketEnd,
                    from2, shownEnd, !from2.equals(bucketStart) || !shownEnd.equals(bucketEnd), seats, occupied, capacity, ratio(occupied, capacity)));
            cursor = next;
        }
        return new SeatOccupancyTrend(Long.toString(branch), hourly ? "hour" : "day", start, end, asOf, SEOUL.getId(),
                new SeatOccupancyTrend.Totals(occupiedTotal, capacityTotal, ratio(occupiedTotal, capacityTotal)), items);
    }

    /** Contract schema {@code Ratio}: one decimal, HALF_UP; a zero denominator is "해당 없음" (M9-D09). */
    static SeatOccupancyTrend.Ratio ratio(long numerator, long denominator) {
        if (denominator == 0) return new SeatOccupancyTrend.Ratio("notApplicable", null, numerator, 0);
        var percent = BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
        return new SeatOccupancyTrend.Ratio("value", percent, numerator, denominator);
    }

    private static ZonedDateTime floor(Instant at, boolean hourly) {
        var local = at.atZone(SEOUL);
        return hourly ? local.truncatedTo(ChronoUnit.HOURS) : local.toLocalDate().atStartOfDay(SEOUL);
    }

    private static Instant instant(String value, String field) {
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeException exception) {
            throw ApiException.validation(field, "시간대 오프셋이 있는 ISO 8601 시각을 입력해 주세요.");
        }
    }

    private static Instant max(Instant a, Instant b) { return a.isAfter(b) ? a : b; }

    private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
}
