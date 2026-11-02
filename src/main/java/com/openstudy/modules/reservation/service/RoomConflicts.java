package com.openstudy.modules.reservation.service;

import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.persistence.DuplicateKeys;

/**
 * The unique keys behind the room commands are the last line of defence after the checks made under lock. If one fires
 * anyway, the client gets the same 409 the check would have given, and throwing rolls the whole command back, so a
 * refused booking leaves no payment. A stored key repeats only when a client key is reused after its idempotency record
 * expired, because it is a hash of the same four values.
 */
final class RoomConflicts {
    private RoomConflicts() {}

    static <T> T translating(Supplier<T> write) {
        try {
            return DuplicateKeys.translate(write);
        } catch (DuplicateKeyException exception) {
            throw conflict(exception);
        }
    }

    private static RuntimeException conflict(DuplicateKeyException exception) {
        String message = String.valueOf(exception.getMessage());
        if (message.contains("uk_room_reservation_slots_1")) return timeConflict();
        if (message.contains("uk_rooms_1")) return duplicateRoomNo();
        if (message.contains("uk_payments_1") || message.contains("uk_room_reservations_1"))
            return ApiException.conflict("IDEMPOTENCY_CONFLICT", "이미 사용한 요청 키입니다.");
        return exception;
    }

    static ApiException timeConflict() {
        return ApiException.conflict("ROOM_TIME_CONFLICT", "같은 시간에 이미 예약이 있습니다. 다른 시간을 선택해 주세요.");
    }

    static ApiException duplicateRoomNo() {
        return ApiException.conflict("DUPLICATE_ROOM_NO", "같은 지점에 이미 있는 룸 번호입니다.");
    }
}
