package com.openstudy.modules.seat.entity;

/** The three non-overlapping states a seat is shown in; an active session wins over the stored state. */
public enum SeatDisplayStatus {
    AVAILABLE("사용 가능"), IN_USE("사용 중"), BLOCKED("운영 중단");

    private final String label;

    SeatDisplayStatus(String label) { this.label = label; }

    public String label() { return label; }
}
