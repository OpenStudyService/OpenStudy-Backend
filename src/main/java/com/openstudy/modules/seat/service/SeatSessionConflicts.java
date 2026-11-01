package com.openstudy.modules.seat.service;

import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.persistence.DuplicateKeys;

/**
 * The unique keys of seat_sessions are the last line of defence behind the checks made under lock. If one of them fires
 * anyway, the client gets the same 409 the check would have given instead of a 500. The start key repeats only when a
 * key is reused after its idempotency record expired, because the stored key is a hash of the same four values.
 */
final class SeatSessionConflicts {
    private SeatSessionConflicts() {}

    static <T> T translating(Supplier<T> insert) {
        try {
            return DuplicateKeys.translate(insert);
        } catch (DuplicateKeyException exception) {
            throw conflict(exception);
        }
    }

    private static RuntimeException conflict(DuplicateKeyException exception) {
        String message = String.valueOf(exception.getMessage());
        if (message.contains("uk_seat_sessions_1")) return ApiException.conflict("SEAT_OCCUPIED", "이미 사용 중인 좌석입니다.");
        if (message.contains("uk_seat_sessions_2")) return ApiException.conflict("CUSTOMER_ALREADY_CHECKED_IN", "이미 입실 중인 고객입니다.");
        if (message.contains("uk_seat_session_moves_1")) return ApiException.conflict("IDEMPOTENCY_CONFLICT", "이미 사용한 요청 키입니다.");
        if (message.contains("uk_seat_sessions_3")) return ApiException.conflict("IDEMPOTENCY_CONFLICT", "이미 사용한 요청 키입니다.");
        return exception;
    }
}
