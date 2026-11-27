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

    private static final String NOT_ALLOWED_MESSAGE = "허용되지 않은 값입니다.";
    private static final String INVALID_ID_MESSAGE = "올바른 ID를 입력해 주세요.";
    private static final String INVALID_DATE_MESSAGE = "올바른 날짜를 입력해 주세요.";
    private static final String ID_FORMAT = "[1-9][0-9]*";

    private FacilityInputs() {
    }

    static FacilityStatus parseStatus(String value, String field) {
        FacilityStatus status = FacilityStatus.ofLabel(value);

        if (status == null) {
            throw ApiException.validation(field, NOT_ALLOWED_MESSAGE);
        }

        return status;
    }

    static FacilityWorkStatus parseWorkStatus(String value, String field) {
        FacilityWorkStatus status = FacilityWorkStatus.ofOpenLabel(value);

        if (status == null) {
            throw ApiException.validation(field, NOT_ALLOWED_MESSAGE);
        }

        return status;
    }

    /** 1~3650 days; a JSON integer only (not a string, a fraction or a boolean). */
    static int parseDays(Object value, String field) {
        boolean isIntegral = value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long;

        if (!isIntegral || ((Number) value).longValue() < 1 || ((Number) value).longValue() > MAX_DAYS) {
            throw ApiException.validation(field, "1~3650 사이의 정수를 입력해 주세요.");
        }

        return ((Number) value).intValue();
    }

    /** Optional free text: absent, null and blank all mean "no content"; anything but a string within the limit is refused. */
    static String parseOptionalText(Map<String, Object> values, String field, int max) {
        Object value = values.get(field);

        if (value == null) {
            return null;
        }

        if (!(value instanceof String text) || text.length() > max) {
            throw ApiException.validation(field, "올바른 값을 입력해 주세요.");
        }

        return text.isBlank() ? null : text.strip();
    }

    /** Optional ID that may be null (cleared). */
    static Long parseOptionalId(Map<String, Object> values, String field) {
        Object value = values.get(field);

        if (value == null) {
            return null;
        }

        if (!(value instanceof String text)) {
            throw ApiException.validation(field, INVALID_ID_MESSAGE);
        }

        return parseId(text, field);
    }

    static LocalDate parseOptionalDate(Map<String, Object> values, String field) {
        Object value = values.get(field);

        if (value == null) {
            return null;
        }

        if (!(value instanceof String text)) {
            throw ApiException.validation(field, INVALID_DATE_MESSAGE);
        }

        return parseDate(text, field);
    }

    static LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw ApiException.validation(field, INVALID_DATE_MESSAGE);
        }
    }

    static long parseId(String value, String field) {
        try {
            if (!value.matches(ID_FORMAT)) {
                throw new NumberFormatException();
            }

            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw ApiException.validation(field, INVALID_ID_MESSAGE);
        }
    }

    static boolean parseFlag(String value, String field) {
        if (value == null || "false".equals(value)) {
            return false;
        }

        if ("true".equals(value)) {
            return true;
        }

        throw ApiException.validation(field, "true 또는 false를 입력해 주세요.");
    }
}
