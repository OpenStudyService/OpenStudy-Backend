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

    private static final String NOT_ALLOWED_MESSAGE = "허용되지 않은 값입니다.";
    private static final int MAX_ZONE_LENGTH = 80;
    private static final int MAX_REASON_LENGTH = 500;
    private static final String ID_FORMAT = "[1-9][0-9]*";

    private SeatInputs() {
    }

    static SeatKind parseKind(String value, String field) {
        return Arrays.stream(SeatKind.values())
                .filter(kind -> kind.label().equals(value))
                .findFirst()
                .orElseThrow(() -> ApiException.validation(field, NOT_ALLOWED_MESSAGE));
    }

    /** Only the two stored states can be requested; "사용 중" is computed and cannot be set. */
    static SeatState parseState(String value, String field) {
        return Arrays.stream(SeatState.values())
                .filter(state -> state.label().equals(value))
                .findFirst()
                .orElseThrow(() -> ApiException.validation(field, NOT_ALLOWED_MESSAGE));
    }

    static SeatDisplayStatus parseDisplayStatus(String value, String field) {
        return Arrays.stream(SeatDisplayStatus.values())
                .filter(status -> status.label().equals(value))
                .findFirst()
                .orElseThrow(() -> ApiException.validation(field, NOT_ALLOWED_MESSAGE));
    }

    static SeatSession.Status parseSessionStatus(String value) {
        return Arrays.stream(SeatSession.Status.values())
                .filter(status -> status.label().equals(value))
                .findFirst()
                .orElseThrow(() -> ApiException.validation("status", NOT_ALLOWED_MESSAGE));
    }

    static Optional<String> parseZone(String value) {
        if (value == null) {
            return Optional.empty();
        }

        String zone = value.strip();

        if (zone.isEmpty() || zone.length() > MAX_ZONE_LENGTH) {
            throw ApiException.validation("zone", "구역은 1~80자여야 합니다.");
        }

        return Optional.of(zone);
    }

    /** The optional 0~500 character reason of check-in and check-out: absent and blank both mean "none". */
    static String parseReason(Map<String, Object> body) {
        if (!body.containsKey("reason")) {
            return null;
        }

        if (!(body.get("reason") instanceof String value) || value.length() > MAX_REASON_LENGTH) {
            throw ApiException.validation("reason", "사유는 500자 이내로 입력해 주세요.");
        }

        return value.isBlank() ? null : value.strip();
    }

    static long parseId(String value, String field) {
        try {
            if (value == null || !value.matches(ID_FORMAT)) {
                throw new NumberFormatException();
            }

            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw ApiException.validation(field, "올바른 ID를 입력해 주세요.");
        }
    }
}
