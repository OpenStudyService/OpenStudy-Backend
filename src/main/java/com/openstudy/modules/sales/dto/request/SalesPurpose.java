package com.openstudy.modules.sales.dto.request;

import java.util.Arrays;

/** payments.purpose with its contract label, in the fixed byPurpose order 이용권 → 스터디룸 → 사물함 (M9-D03). */
public enum SalesPurpose {
    PASS("이용권"),
    ROOM("스터디룸"),
    LOCKER("사물함");

    public final String label;
    SalesPurpose(String label) { this.label = label; }

    /** null when the label is not a contract value; the caller turns that into its own 422. */
    public static SalesPurpose fromLabel(String value) {
        return Arrays.stream(values()).filter(purpose -> purpose.label.equals(value)).findFirst().orElse(null);
    }
}
