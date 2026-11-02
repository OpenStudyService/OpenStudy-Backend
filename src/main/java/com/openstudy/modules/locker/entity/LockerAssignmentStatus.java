package com.openstudy.modules.locker.entity;

/**
 * Stored assignment status. PAYMENT_PENDING exists only in V1: a Mock payment settles inside the request, so it is never
 * stored or shown. Only ACTIVE holds its period; RETURNED, PAYMENT_FAILED and CANCELLED hold nothing.
 */
public enum LockerAssignmentStatus {
    PAYMENT_PENDING(null), PAYMENT_FAILED("결제 실패"), ACTIVE("배정"), RETURNED("반납"), CANCELLED("취소");

    private final String label;

    LockerAssignmentStatus(String label) { this.label = label; }

    public String label() {
        if (label == null) throw new IllegalStateException("Mock assignments are never stored as PAYMENT_PENDING");
        return label;
    }

    public boolean labelled(String value) { return label != null && label.equals(value); }
}
