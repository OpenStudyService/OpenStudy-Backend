package com.openstudy.modules.workforce.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Map;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.workforce.entity.ShiftStatus;

/** Wire values, Seoul days and the shift time rules (M8-D03) of the workforce contract, and its request-key helpers. */
final class ShiftInputs {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final long MAX_MINUTES = 24 * 60;
    /** What DATETIME(6) can hold; a syntactically valid year beyond it must stay a 422, not a failed bind. */
    private static final Instant EARLIEST = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-01-01T00:00:00Z");

    private ShiftInputs() {}

    /** The Seoul day an instant belongs to: a shift's workDate is the day it starts, never the UTC date. */
    static LocalDate day(Instant at) { return LocalDate.ofInstant(at, SEOUL); }

    static Instant startOf(LocalDate day) { return day.atStartOfDay(SEOUL).toInstant(); }

    /**
     * ISO 8601 with an offset ("Z" or "+09:00"); a time without one is a format error, as is anything else. Whether it
     * falls on a whole minute is a time rule, judged with the other rules once the final values are known.
     */
    static Instant instant(Object value, String field) {
        if (value instanceof String text && text.length() <= 40) {
            try {
                Instant parsed = OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
                if (!parsed.isBefore(EARLIEST) && parsed.isBefore(LATEST)) return parsed;
            } catch (DateTimeException exception) {
                // Reported below, together with an out-of-range year.
            }
        }
        throw ApiException.validation(field, "시간대 오프셋이 있는 ISO 8601 시각을 입력해 주세요.");
    }

    /** A JSON integer that is not negative. How large it may be depends on the shift's length (SHIFT_BREAK_INVALID). */
    static int breakMinutes(Map<String, Object> body) {
        Object value = body.get("plannedBreakMinutes");
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() < 0)
            throw ApiException.validation("plannedBreakMinutes", "0 이상의 정수를 입력해 주세요.");
        return (int) Math.min(((Number) value).longValue(), Integer.MAX_VALUE);
    }

    /**
     * M8-D03 on the final values: whole minutes, 0 &lt; length ≤ 24 hours, then 0 ≤ break &lt; length. Korea has no daylight
     * saving, so 24 hours is always 1440 minutes, and a shift may cross midnight.
     */
    static void requireValid(Instant start, Instant end, int breakMinutes) {
        if (!wholeMinute(start) || !wholeMinute(end))
            throw ApiException.unprocessable("SHIFT_TIME_INVALID", "근무 시각은 분 단위로 입력해 주세요.");
        long minutes = Duration.between(start, end).toMinutes();
        if (!end.isAfter(start) || minutes > MAX_MINUTES)
            throw ApiException.unprocessable("SHIFT_TIME_INVALID", "종료는 시작보다 늦고 근무 길이는 24시간 이하여야 합니다.");
        if (breakMinutes >= minutes)
            throw ApiException.unprocessable("SHIFT_BREAK_INVALID", "예정 휴게 시간은 근무 길이보다 짧아야 합니다.");
    }

    private static boolean wholeMinute(Instant at) { return at.getEpochSecond() % 60 == 0 && at.getNano() == 0; }

    static ShiftStatus status(String value) {
        return Arrays.stream(ShiftStatus.values()).filter(status -> status.label().equals(value)).findFirst()
                .orElseThrow(() -> ApiException.validation("status", "허용되지 않은 값입니다."));
    }

    /** Exactly yyyy-MM-dd: java.time also reads years past 9999, which the stored DATETIME cannot reach. */
    static LocalDate date(String value, String field) {
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || value.startsWith("0")) throw ApiException.validation(field, "올바른 날짜를 입력해 주세요.");
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw ApiException.validation(field, "올바른 날짜를 입력해 주세요.");
        }
    }

    static long id(String value, String field) {
        try {
            if (!value.matches("[1-9][0-9]*")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw ApiException.validation(field, "올바른 ID를 입력해 주세요.");
        }
    }

    static boolean flag(String value, String field) {
        if (value == null || "false".equals(value)) return false;
        if ("true".equals(value)) return true;
        throw ApiException.validation(field, "true 또는 false를 입력해 주세요.");
    }

    static void validKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128)
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
    }

    static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
