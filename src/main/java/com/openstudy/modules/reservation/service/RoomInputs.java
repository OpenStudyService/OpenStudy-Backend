package com.openstudy.modules.reservation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.reservation.entity.RoomReservationStatus;
import com.openstudy.modules.reservation.entity.RoomState;

/** Wire labels and number ranges of the room contract, and the request-key helpers shared by its commands. */
final class RoomInputs {
    private RoomInputs() {}

    static RoomState state(String value, String field) {
        return Arrays.stream(RoomState.values()).filter(state -> state.label().equals(value)).findFirst()
                .orElseThrow(() -> ApiException.validation(field, "허용되지 않은 값입니다."));
    }

    static RoomReservationStatus reservationStatus(String value) {
        return Arrays.stream(RoomReservationStatus.values()).filter(status -> status.labelled(value)).findFirst()
                .orElseThrow(() -> ApiException.validation("status", "허용되지 않은 값입니다."));
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

    /** The request-key columns are globally unique and 120 characters, so they hold a hash (contract "트랜잭션 경계"). */
    static String storedKey(String prefix, long actor, String operation, String scopeKey, String key) {
        return prefix + HexFormat.of().formatHex(digest(actor + "\n" + operation + "\n" + scopeKey + "\n" + key));
    }

    static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
