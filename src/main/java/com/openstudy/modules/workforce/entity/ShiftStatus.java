package com.openstudy.modules.workforce.entity;

/** Stored shift status, shown as the contract's {@code ShiftStatus}. A cancelled shift keeps its row and history. */
public enum ShiftStatus {
    SCHEDULED("예정"), CANCELLED("취소");

    private final String label;

    ShiftStatus(String label) { this.label = label; }

    public String label() { return label; }
}
