package com.openstudy.modules.locker.entity;

/** PENDING exists only in V1: a Mock payment settles inside the request. */
public enum LockerChargeStatus {
    PENDING(null), APPLIED("적용"), FAILED("실패");

    private final String label;

    LockerChargeStatus(String label) { this.label = label; }

    public String label() {
        if (label == null) throw new IllegalStateException("Mock charges are never stored as PENDING");
        return label;
    }
}
