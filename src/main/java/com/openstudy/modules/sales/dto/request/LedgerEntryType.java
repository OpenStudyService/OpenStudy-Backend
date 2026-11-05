package com.openstudy.modules.sales.dto.request;

import java.util.Arrays;

/** One ledger item is one source row: an approved payment or a refund (FR-AGG-18). */
public enum LedgerEntryType {
    APPROVAL("승인"),
    REFUND("환불");

    public final String label;
    LedgerEntryType(String label) { this.label = label; }

    public static LedgerEntryType fromLabel(String value) {
        return Arrays.stream(values()).filter(type -> type.label.equals(value)).findFirst().orElse(null);
    }
}
