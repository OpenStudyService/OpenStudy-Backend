package com.openstudy.modules.seat.entity;

/**
 * Stored operating state. "사용 중" is never stored: it is derived from an active seat session (V1 {@code seats} comment),
 * so it is not a value of this enum.
 */
public enum SeatState {
    AVAILABLE("사용 가능"), BLOCKED("운영 중단");

    private final String label;

    SeatState(String label) { this.label = label; }

    public String label() { return label; }
}
