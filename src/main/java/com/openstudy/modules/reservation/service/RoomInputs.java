package com.openstudy.modules.reservation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.reservation.entity.RoomReservationStatus;
import com.openstudy.modules.reservation.entity.RoomState;

/** Wire labels and number ranges of the room contract, and the request-key helpers shared by its commands. */
final class RoomInputs {

    private static final String NOT_ALLOWED_MESSAGE = "허용되지 않은 값입니다.";
    private static final String ID_FORMAT = "[1-9][0-9]*";
    private static final int MIN_KEY_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 128;
    private static final String HASH_ALGORITHM = "SHA-256";

    private RoomInputs() {
    }

    static RoomState parseState(String value, String field) {
        return Arrays.stream(RoomState.values())
                .filter(state -> state.label().equals(value))
                .findFirst()
                .orElseThrow(() -> ApiException.validation(field, NOT_ALLOWED_MESSAGE));
    }

    static RoomReservationStatus parseReservationStatus(String value) {
        return Arrays.stream(RoomReservationStatus.values())
                .filter(status -> status.labelled(value))
                .findFirst()
                .orElseThrow(() -> ApiException.validation("status", NOT_ALLOWED_MESSAGE));
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

    /** The request-key columns are globally unique and 120 characters, so they hold a hash (contract "트랜잭션 경계"). */
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
