package com.openstudy.modules.locker.service;

import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.persistence.DuplicateKeys;

/**
 * The unique keys behind the locker commands are the last line of defence after the checks made under lock. If one fires
 * anyway, the client gets the same 409 the check would have given, and throwing rolls the whole command back, so a
 * refused assignment leaves no payment. A stored key repeats only when a client key is reused after its idempotency
 * record expired, because it is a hash of the same four values.
 */
final class LockerConflicts {
    private LockerConflicts() {}

    static <T> T translating(Supplier<T> write) {
        try {
            return DuplicateKeys.translate(write);
        } catch (DuplicateKeyException exception) {
            throw conflict(exception);
        }
    }

    private static RuntimeException conflict(DuplicateKeyException exception) {
        String message = String.valueOf(exception.getMessage());
        if (message.contains("uk_lockers_1")) return duplicateLockerNo();
        if (message.contains("uk_payments_1")) return ApiException.conflict("IDEMPOTENCY_CONFLICT", "이미 사용한 요청 키입니다.");
        return exception;
    }

    static ApiException duplicateLockerNo() {
        return ApiException.conflict("DUPLICATE_LOCKER_NO", "같은 지점에 이미 있는 사물함 번호입니다.");
    }

    static ApiException periodConflict() {
        return ApiException.conflict("LOCKER_PERIOD_CONFLICT", "같은 기간에 이미 배정이 있습니다. 다른 기간을 선택해 주세요.");
    }

    static ApiException notReturned() {
        return ApiException.conflict("LOCKER_NOT_RETURNED", "기간이 끝났지만 반납되지 않은 배정이 있습니다. 반납 후 배정해 주세요.");
    }
}
