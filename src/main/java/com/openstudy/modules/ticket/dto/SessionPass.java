package com.openstudy.modules.ticket.dto;

/** The pass of a seat session with its current balance; ids are strings as on every API response. */
public record SessionPass(String id, String kind, String productName, String purchaseBranchId, Long remainingMinutes,
        Long remainingCreditKrw) {
    /** Only a time pass loses minutes, so only there can deducted minutes be derived from the session row. */
    public boolean deductsMinutes() { return ProductTerms.TIME.equals(kind); }
}
