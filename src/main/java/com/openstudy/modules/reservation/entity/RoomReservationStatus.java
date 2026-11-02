package com.openstudy.modules.reservation.entity;

import java.util.List;

/**
 * Stored reservation status. PAYMENT_PENDING exists only in V1: a Mock payment settles inside the request, so it is
 * never stored or shown (space-operations-api.md). Only CONFIRMED and IN_USE hold their time.
 */
public enum RoomReservationStatus {
    PAYMENT_PENDING(null), CONFIRMED("확정"), PAYMENT_FAILED("결제 실패"), IN_USE("이용 중"), COMPLETED("완료"), CANCELLED("취소");

    public static final List<RoomReservationStatus> ACTIVE = List.of(CONFIRMED, IN_USE);

    private final String label;

    RoomReservationStatus(String label) { this.label = label; }

    public String label() {
        if (label == null) throw new IllegalStateException("Mock reservations are never stored as PAYMENT_PENDING");
        return label;
    }

    public boolean labelled(String value) { return label != null && label.equals(value); }
}
