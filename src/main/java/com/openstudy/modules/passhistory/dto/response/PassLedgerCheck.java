package com.openstudy.modules.passhistory.dto.response;

/** Contract schema {@code PassLedgerCheck}: over the whole ledger, issued + ledger = remaining (FR-PASS-09). */
public record PassLedgerCheck(String passId, String kind, String productName, String status, String purchaseBranchId,
        Long issuedMinutes, Long issuedCreditKrw, long ledgerMinutes, long ledgerKrw, Long remainingMinutes, Long remainingCreditKrw) {}
