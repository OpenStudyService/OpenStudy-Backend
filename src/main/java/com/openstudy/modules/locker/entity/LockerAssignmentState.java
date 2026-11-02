package com.openstudy.modules.locker.entity;

/** The computed {@code assignmentState} of a locker. It is never stored: today decides it (space-operations-api.md). */
public enum LockerAssignmentState {
    AVAILABLE("사용 가능"), IN_USE("사용 중"), BLOCKED("운영 중단");

    private final String label;

    LockerAssignmentState(String label) { this.label = label; }

    public String label() { return label; }
}
