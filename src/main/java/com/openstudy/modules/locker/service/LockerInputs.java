package com.openstudy.modules.locker.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.locker.entity.LockerAssignmentState;
import com.openstudy.modules.locker.entity.LockerAssignmentStatus;
import com.openstudy.modules.locker.entity.LockerState;

/** Wire labels, dates and number ranges of the locker contract, and the request-key helpers shared by its commands. */
final class LockerInputs {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String NOT_ALLOWED_MESSAGE = "허용되지 않은 값입니다.";
    private static final String INVALID_DATE_MESSAGE = "올바른 날짜를 입력해 주세요.";
    private static final String DATE_FORMAT = "[0-9]{4}-[0-9]{2}-[0-9]{2}";
    private static final String ID_FORMAT = "[1-9][0-9]*";
    private static final int MIN_KEY_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 128;
    private static final String HASH_ALGORITHM = "SHA-256";

    private LockerInputs() {
    }

    /** The business date: an instant is one day in UTC and the next in Korea from 15:00 UTC on. */
    static LocalDate toBusinessDate(Instant at) {
        return LocalDate.ofInstant(at, SEOUL);
    }

    /** Where a business date begins, the instant a locker period starts. */
    static Instant toStartInstant(LocalDate day) {
        return day.atStartOfDay(SEOUL).toInstant();
    }

    static LockerState parseState(String value, String field) {
        return Arrays.stream(LockerState.values())
                .filter(state -> state.label().equals(value))
                .findFirst()
                .orElseThrow(() -> ApiException.validation(field, NOT_ALLOWED_MESSAGE));
    }

    static LockerAssignmentState parseAssignmentState(String value) {
        return Arrays.stream(LockerAssignmentState.values())
                .filter(state -> state.label().equals(value))
                .findFirst()
                .orElseThrow(() -> ApiException.validation("assignmentState", NOT_ALLOWED_MESSAGE));
    }

    static LockerAssignmentStatus parseAssignmentStatus(String value) {
        return Arrays.stream(LockerAssignmentStatus.values())
                .filter(status -> status.labelled(value))
                .findFirst()
                .orElseThrow(() -> ApiException.validation("status", NOT_ALLOWED_MESSAGE));
    }

    /** Exactly yyyy-MM-dd: java.time also reads years past 9999, which a DATE column cannot hold. */
    static LocalDate parseDate(String value, String field) {
        try {
            if (!value.matches(DATE_FORMAT)) {
                throw ApiException.validation(field, INVALID_DATE_MESSAGE);
            }

            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw ApiException.validation(field, INVALID_DATE_MESSAGE);
        }
    }

    /** A JSON integer inside the schema's range. Numbers with a fraction and values beyond long are refused. */
    static long parseInteger(Map<String, Object> body, String field, long minimum, long maximum) {
        Object value = body.get(field);
        boolean isIntegral = value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long;

        if (!isIntegral || ((Number) value).longValue() < minimum || ((Number) value).longValue() > maximum) {
            throw ApiException.validation(field, "올바른 값을 입력해 주세요.");
        }

        return ((Number) value).longValue();
    }

    static long parseId(String value, String field) {
        try {
            if (!value.matches(ID_FORMAT)) {
                throw new NumberFormatException();
            }

            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw ApiException.validation(field, "올바른 ID를 입력해 주세요.");
        }
    }

    static void requireValidKey(String key) {
        boolean isValidKey = key != null && key.length() >= MIN_KEY_LENGTH && key.length() <= MAX_KEY_LENGTH;

        if (!isValidKey) {
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
        }
    }

    /** payments.idempotency_key is globally unique and 120 characters, so it holds a hash (contract "트랜잭션 경계"). */
    static String toStoredKey(String prefix, long actor, String operation, String scopeKey, String key) {
        String source = actor + "\n" + operation + "\n" + scopeKey + "\n" + key;

        return prefix + HexFormat.of().formatHex(digest(source));
    }

    static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
