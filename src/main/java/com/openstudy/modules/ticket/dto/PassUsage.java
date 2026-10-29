package com.openstudy.modules.ticket.dto;

/** Refund-relevant state of the pass bought by one payment, judged at a given instant (ADR-M4-002). */
public record PassUsage(long passId, long purchaseBranchId, boolean used, boolean expired, boolean refunded) {
    /** Unused, unexpired and not yet refunded; the caller still checks payments:write and the payment status. */
    public boolean refundable() { return !used && !expired && !refunded; }
}
