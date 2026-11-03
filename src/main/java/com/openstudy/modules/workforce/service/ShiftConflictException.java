package com.openstudy.modules.workforce.service;

import java.util.List;

import com.openstudy.modules.workforce.dto.response.ShiftConflict;

/** 409 SHIFT_CONFLICT with what is taken. Thrown inside the command, so the transaction rolls back like any other refusal. */
public class ShiftConflictException extends RuntimeException {
    public static final String CODE = "SHIFT_CONFLICT";
    private final List<ShiftConflict> conflicts;

    ShiftConflictException(List<ShiftConflict> conflicts) {
        super("같은 직원의 다른 근무 또는 휴가와 시간이 겹칩니다.");
        this.conflicts = List.copyOf(conflicts);
    }

    public List<ShiftConflict> conflicts() { return conflicts; }
}
