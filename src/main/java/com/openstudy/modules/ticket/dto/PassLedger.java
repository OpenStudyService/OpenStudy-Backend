package com.openstudy.modules.ticket.dto;

import java.time.Instant;
import java.util.List;

/**
 * One pass with its whole balance ledger, for a customer's history. {@code ledgerMinutes} and {@code ledgerKrw} sum every
 * row, so issued + ledger = remaining (FR-PASS-09). Ids stay numeric: the caller compares branches before it shows them.
 */
public record PassLedger(long id, String kind, String productName, String status, long purchaseBranchId, long paymentId,
        Long issuedMinutes, Long issuedCreditKrw, long ledgerMinutes, long ledgerKrw, Long remainingMinutes, Long remainingCreditKrw,
        List<Entry> entries) {
    /** A balance change: a manual {@code adjustment}, or a deduction of the stay {@code seatSessionId}. */
    public record Entry(boolean adjustment, Long seatSessionId, long deltaMinutes, long deltaKrw, Long actorId, String reason, Instant at) {}
}
