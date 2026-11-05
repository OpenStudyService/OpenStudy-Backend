package com.openstudy.modules.sales.dto.request;

import java.util.Arrays;

/**
 * Order of the branch comparison (FR-AGG-02). An amount tie falls back to branchId in the same direction; REQUESTED is
 * the order branchIds were sent in (ID ascending when omitted) and ignores the direction.
 */
public enum BranchRevenueSort {
    NET_AMOUNT("netAmount"),
    APPROVED_AMOUNT("approvedAmount"),
    REFUND_AMOUNT("refundAmount"),
    BRANCH_ID("branchId"),
    REQUESTED("requested");

    public final String wire;
    BranchRevenueSort(String wire) { this.wire = wire; }

    public static BranchRevenueSort fromWire(String value) {
        return Arrays.stream(values()).filter(sort -> sort.wire.equals(value)).findFirst().orElse(null);
    }
}
