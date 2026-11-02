package com.openstudy.modules.locker.entity;

public enum LockerChargeKind {
    INITIAL("최초"), EXTENSION("연장");

    private final String label;

    LockerChargeKind(String label) { this.label = label; }

    public String label() { return label; }
}
