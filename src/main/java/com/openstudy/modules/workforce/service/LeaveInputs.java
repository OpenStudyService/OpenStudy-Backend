package com.openstudy.modules.workforce.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.workforce.entity.LeaveRequest;

/** Wire values and the day rules of leave (M12-D06, workforce-requests-api.md "기간 규칙"). */
final class LeaveInputs {

    static final BigDecimal HALF = new BigDecimal("0.5");
    static final BigDecimal MAX_DAYS = new BigDecimal("366");
    private static final int STORED_SCALE = 1;

    private LeaveInputs() {
    }

    /** A JSON number as exact days with one decimal place; anything that is not a number is a format error. */
    static BigDecimal parseDays(Map<String, Object> body, String field) {
        Object value = body.get(field);
        boolean infinite = value instanceof Double wide && !Double.isFinite(wide)
                || value instanceof Float narrow && !Float.isFinite(narrow);

        if (!(value instanceof Number) || infinite) {
            throw ApiException.validation(field, "일수는 숫자로 입력해 주세요.");
        }

        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            throw ApiException.validation(field, "일수는 숫자로 입력해 주세요.");
        }
    }

    /** Half-day steps: twice the value is a whole number. Scaled to the stored one decimal place. */
    static boolean isHalfDays(BigDecimal days) {
        return days.multiply(BigDecimal.TWO).stripTrailingZeros().scale() <= 0;
    }

    static BigDecimal toStored(BigDecimal days) {
        return days.setScale(STORED_SCALE, RoundingMode.UNNECESSARY);
    }

    /** A JSON integer, or a format error. */
    static long parseInteger(Map<String, Object> body, String field) {
        Object value = body.get(field);
        boolean integer = value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long;

        if (!integer) {
            throw ApiException.validation(field, "정수를 입력해 주세요.");
        }

        return ((Number) value).longValue();
    }

    static LocalDate parseDate(Map<String, Object> body, String field) {
        if (!(body.get(field) instanceof String text)) {
            throw ApiException.validation(field, "올바른 날짜를 입력해 주세요.");
        }

        return ShiftInputs.parseDate(text, field);
    }

    /**
     * M12-D06 on a request: startsOn ≤ endsOn in one year, half-day steps, 0.5 ≤ days ≤ the calendar days of the period.
     * Every refusal is 422 LEAVE_PERIOD_INVALID naming the field to fix.
     */
    static void requirePeriod(LocalDate startsOn, LocalDate endsOn, BigDecimal days) {
        if (endsOn.isBefore(startsOn)) {
            throw createPeriodError("endsOn", "종료일은 시작일보다 앞설 수 없습니다.");
        }

        if (endsOn.getYear() != startsOn.getYear()) {
            throw createPeriodError("endsOn", "연도를 넘는 휴가는 나누어 신청해 주세요.");
        }

        if (!isHalfDays(days)) {
            throw createPeriodError("requestedDays", "휴가 일수는 0.5일 단위로 입력해 주세요.");
        }

        long calendar = ChronoUnit.DAYS.between(startsOn, endsOn) + 1;
        boolean outOfRange = days.compareTo(HALF) < 0 || days.compareTo(BigDecimal.valueOf(calendar)) > 0;

        if (outOfRange) {
            throw createPeriodError("requestedDays", "휴가 일수는 0.5일 이상, 기간의 달력 일수 이하여야 합니다.");
        }
    }

    private static ApiException createPeriodError(String field, String message) {
        return ApiException.invalid("LEAVE_PERIOD_INVALID", "휴가 기간이나 일수를 확인해 주세요.", field, message);
    }

    static ApiException createGrantError(String field, String message) {
        return ApiException.invalid("LEAVE_GRANT_INVALID", "휴가 부여 내용을 확인해 주세요.", field, message);
    }

    static LeaveRequest.Status parseStatus(String value) {
        for (LeaveRequest.Status status : LeaveRequest.Status.values()) {
            if (status.label.equals(value)) {
                return status;
            }
        }

        throw ApiException.validation("status", "허용되지 않은 값입니다.");
    }
}
