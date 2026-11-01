package com.openstudy.modules.seat.service;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.seat.entity.SeatDisplayStatus;
import com.openstudy.modules.seat.entity.SeatKind;
import com.openstudy.modules.seat.entity.SeatSession;
import com.openstudy.modules.seat.entity.SeatState;

/** Wire labels of the seat contract. */
public final class SeatInputs {
    private SeatInputs() {}

    static SeatKind kind(String value, String field) {
        return Arrays.stream(SeatKind.values()).filter(kind -> kind.label().equals(value)).findFirst()
                .orElseThrow(() -> ApiException.validation(field, "허용되지 않은 값입니다."));
    }

    /** Only the two stored states can be requested; "사용 중" is computed and cannot be set. */
    static SeatState state(String value, String field) {
        return Arrays.stream(SeatState.values()).filter(state -> state.label().equals(value)).findFirst()
                .orElseThrow(() -> ApiException.validation(field, "허용되지 않은 값입니다."));
    }

    static SeatDisplayStatus display(String value, String field) {
        return Arrays.stream(SeatDisplayStatus.values()).filter(status -> status.label().equals(value)).findFirst()
                .orElseThrow(() -> ApiException.validation(field, "허용되지 않은 값입니다."));
    }

    static SeatSession.Status sessionStatus(String value) {
        return Arrays.stream(SeatSession.Status.values()).filter(status -> status.label().equals(value)).findFirst()
                .orElseThrow(() -> ApiException.validation("status", "허용되지 않은 값입니다."));
    }

    static Optional<String> zone(String value) {
        if (value == null) return Optional.empty();
        String zone = value.strip();
        if (zone.isEmpty() || zone.length() > 80) throw ApiException.validation("zone", "구역은 1~80자여야 합니다.");
        return Optional.of(zone);
    }

    /** The optional 0~500 character reason of check-in and check-out: absent and blank both mean "none". */
    static String reason(Map<String, Object> body) {
        if (!body.containsKey("reason")) return null;
        if (!(body.get("reason") instanceof String value) || value.length() > 500)
            throw ApiException.validation("reason", "사유는 500자 이내로 입력해 주세요.");
        return value.isBlank() ? null : value.strip();
    }

    static long id(String value, String field) {
        try {
            if (value == null || !value.matches("[1-9][0-9]*")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw ApiException.validation(field, "올바른 ID를 입력해 주세요.");
        }
    }
}
