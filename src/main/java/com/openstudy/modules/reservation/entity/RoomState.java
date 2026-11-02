package com.openstudy.modules.reservation.entity;

/** Stored operating state of a room, shown as the contract's {@code SpaceStatus}. */
public enum RoomState {
    AVAILABLE("사용 가능"), BLOCKED("운영 중단");

    private final String label;

    RoomState(String label) { this.label = label; }

    public String label() { return label; }
}
