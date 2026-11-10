package com.openstudy.modules.audit.dto.request;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.openstudy.common.exception.ApiException;

/**
 * The validated query of GET /audit-events (account-collab-api.md "검색", M14-D12). Only the contract's parameters are
 * accepted, each at most once. The period is Asia/Seoul days, both ends included: none given is the last seven days with
 * today, one given fills the other to a seven-day range, and more than 92 days is PERIOD_TOO_LONG.
 */
public record AuditSearchCriteria(LocalDate from, LocalDate to, String entityType, Long entityId, String action,
        Long actorEmployeeId, Long branchId, String result, String requestId, long page, int pageSize) {
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    public static final int DEFAULT_DAYS = 7;
    public static final int MAX_DAYS = 92;
    public static final Set<String> PARAMETERS = Set.of("from", "to", "entityType", "entityId", "action", "actorEmployeeId",
            "branchId", "result", "requestId", "page", "pageSize");
    private static final Set<String> RESULTS = Set.of("SUCCESS", "DENIED", "FAILED");
    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final long MAX_PAGE = 9_007_199_254_740_991L;

    /** First instant of {@code from} (inclusive) in UTC. */
    public Instant start() { return from.atStartOfDay(SEOUL).toInstant(); }

    /** First instant of the day after {@code to} (exclusive) in UTC. */
    public Instant end() { return to.plusDays(1).atStartOfDay(SEOUL).toInstant(); }

    public long offset() { return (page - 1) * pageSize; }

    public static AuditSearchCriteria parse(Map<String, String[]> query, Clock clock) {
        for (var entry : query.entrySet()) {
            if (!PARAMETERS.contains(entry.getKey())) throw ApiException.validation(entry.getKey(), "허용되지 않은 검색 조건입니다.");
            if (entry.getValue().length != 1) throw ApiException.validation(entry.getKey(), "검색 조건은 한 번만 입력해 주세요.");
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), SEOUL);
        LocalDate from = date(query, "from");
        LocalDate to = date(query, "to");
        if (from == null && to == null) {
            to = today;
            from = today.minusDays(DEFAULT_DAYS - 1);
        } else if (to == null) {
            to = from.plusDays(DEFAULT_DAYS - 1);
        } else if (from == null) {
            from = to.minusDays(DEFAULT_DAYS - 1);
        }
        if (to.isBefore(from)) throw ApiException.validation("to", "종료일은 시작일 이후여야 합니다.");
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS)
            throw ApiException.invalid("PERIOD_TOO_LONG", "기간은 최대 92일입니다.", "to", "기간을 줄여 주세요.");
        String entityType = text(query, "entityType", 80);
        Long entityId = id(query, "entityId");
        if (entityId != null && entityType == null)
            throw ApiException.validation("entityId", "대상 ID는 대상 유형과 함께 입력해 주세요.");
        String result = text(query, "result", 7);
        if (result != null && !RESULTS.contains(result)) throw ApiException.validation("result", "결과는 SUCCESS, DENIED, FAILED 중 하나입니다.");
        long page = number(query, "page", 1, 1, MAX_PAGE);
        int pageSize = (int) number(query, "pageSize", 20, 1, 100);
        return new AuditSearchCriteria(from, to, entityType, entityId, text(query, "action", 80), id(query, "actorEmployeeId"),
                id(query, "branchId"), result, text(query, "requestId", 128), page, pageSize);
    }

    private static String value(Map<String, String[]> query, String name) {
        String[] values = query.get(name);
        return values == null ? null : values[0];
    }

    private static LocalDate date(Map<String, String[]> query, String name) {
        String value = value(query, name);
        if (value == null) return null;
        try {
            if (value.length() != 10) throw new DateTimeParseException("length", value, 0);
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw ApiException.validation(name, "날짜는 YYYY-MM-DD 형식이어야 합니다.");
        }
    }

    private static String text(Map<String, String[]> query, String name, int max) {
        String value = value(query, name);
        if (value == null) return null;
        if (value.isEmpty() || value.length() > max) throw ApiException.validation(name, "1~" + max + "자로 입력해 주세요.");
        return value;
    }

    private static Long id(Map<String, String[]> query, String name) {
        String value = value(query, name);
        if (value == null) return null;
        if (!ID.matcher(value).matches()) throw ApiException.validation(name, "ID 형식이 아닙니다.");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw ApiException.validation(name, "ID 형식이 아닙니다.");
        }
    }

    private static long number(Map<String, String[]> query, String name, long fallback, long min, long max) {
        String value = value(query, name);
        if (value == null) return fallback;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < min || parsed > max) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw ApiException.validation(name, min + "~" + max + " 사이의 숫자여야 합니다.");
        }
    }
}
