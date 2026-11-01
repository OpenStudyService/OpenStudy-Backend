package com.openstudy.modules.seat.entity;

/** Stored seat type (V1 {@code seats.kind}). */
public enum SeatKind {
    GENERAL("일반"), MONITOR("모니터"), OTHER("기타");

    private final String label;

    SeatKind(String label) { this.label = label; }

    public String label() { return label; }
}
