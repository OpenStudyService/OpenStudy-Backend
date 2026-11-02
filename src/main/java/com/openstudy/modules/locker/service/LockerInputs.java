package com.openstudy.modules.locker.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

    private LockerInputs() {}

    /** The business date: an instant is one day in UTC and the next in Korea from 15:00 UTC on. */
    static LocalDate today(Instant at) { return LocalDate.ofInstant(at, SEOUL); }

    static LockerState state(String value, String field) {
        return Arrays.stream(LockerState.values()).filter(state -> state.label().equals(value)).findFirst()
                .orElseThrow(() -> ApiException.validation(field, "허용되지 않은 값입니다."));
    }

    static LockerAssignmentState assignmentState(String value) {
        return Arrays.stream(LockerAssignmentState.values()).filter(state -> state.label().equals(value)).findFirst()
                .orElseThrow(() -> ApiException.validation("assignmentState", "허용되지 않은 값입니다."));
    }

    static LockerAssignmentStatus assignmentStatus(String value) {
        return Arrays.stream(LockerAssignmentStatus.values()).filter(status -> status.labelled(value)).findFirst()
                .orElseThrow(() -> ApiException.validation("status", "허용되지 않은 값입니다."));
    }

    /** Exactly yyyy-MM-dd: java.time also reads years past 9999, which a DATE column cannot hold. */
    static LocalDate date(String value, String field) {
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw ApiException.validation(field, "올바른 날짜를 입력해 주세요.");
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw ApiException.validation(field, "올바른 날짜를 입력해 주세요.");
        }
    }

    /** A JSON integer inside the schema's range. Numbers with a fraction and values beyond long are refused. */
    static long integer(Map<String, Object> body, String field, long minimum, long maximum) {
        Object value = body.get(field);
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() < minimum || ((Number) value).longValue() > maximum)
            throw ApiException.validation(field, "올바른 값을 입력해 주세요.");
        return ((Number) value).longValue();
    }

    static long id(String value, String field) {
        try {
            if (!value.matches("[1-9][0-9]*")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw ApiException.validation(field, "올바른 ID를 입력해 주세요.");
        }
    }

    static void validKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128)
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
    }

    /** payments.idempotency_key is globally unique and 120 characters, so it holds a hash (contract "트랜잭션 경계"). */
    static String storedKey(String prefix, long actor, String operation, String scopeKey, String key) {
        return prefix + HexFormat.of().formatHex(digest(actor + "\n" + operation + "\n" + scopeKey + "\n" + key));
    }

    static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
