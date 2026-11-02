package com.openstudy.modules.locker.entity;

/** Stored operating state of a locker, shown as the contract's {@code SpaceStatus}. */
public enum LockerState {
    AVAILABLE("사용 가능"), BLOCKED("운영 중단");

    private final String label;

    LockerState(String label) { this.label = label; }

    public String label() { return label; }
}
