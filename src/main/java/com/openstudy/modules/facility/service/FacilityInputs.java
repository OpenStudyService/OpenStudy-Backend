package com.openstudy.modules.facility.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.facility.entity.FacilityStatus;
import com.openstudy.modules.facility.entity.FacilityWorkStatus;

/** Wire labels and field rules of the facility contract. */
public final class FacilityInputs {
    static final int MAX_DAYS = 3650;

    private FacilityInputs() {}

    static FacilityStatus status(String value, String field) {
        var status = FacilityStatus.ofLabel(value);
        if (status == null) throw ApiException.validation(field, "허용되지 않은 값입니다.");
        return status;
    }

    static FacilityWorkStatus workStatus(String value, String field) {
        var status = FacilityWorkStatus.ofOpenLabel(value);
        if (status == null) throw ApiException.validation(field, "허용되지 않은 값입니다.");
        return status;
    }

    /** 1~3650 days; a JSON integer only (not a string, a fraction or a boolean). */
    static int days(Object value, String field) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() < 1 || ((Number) value).longValue() > MAX_DAYS)
            throw ApiException.validation(field, "1~3650 사이의 정수를 입력해 주세요.");
        return ((Number) value).intValue();
    }

    /** Optional free text: absent, null and blank all mean "no content"; anything but a string within the limit is refused. */
    static String optionalText(Map<String, Object> values, String field, int max) {
        Object value = values.get(field);
        if (value == null) return null;
        if (!(value instanceof String text) || text.length() > max) throw ApiException.validation(field, "올바른 값을 입력해 주세요.");
        return text.isBlank() ? null : text.strip();
    }

    /** Optional ID that may be null (cleared). */
    static Long optionalId(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) return null;
        if (!(value instanceof String text)) throw ApiException.validation(field, "올바른 ID를 입력해 주세요.");
        return id(text, field);
    }

    static LocalDate optionalDate(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) return null;
        if (!(value instanceof String text)) throw ApiException.validation(field, "올바른 날짜를 입력해 주세요.");
        return date(text, field);
    }

    static LocalDate date(String value, String field) {
        try {
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
}
