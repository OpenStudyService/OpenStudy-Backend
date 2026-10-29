package com.openstudy.modules.ticket.dto;

import java.time.Instant;
import com.openstudy.modules.customer.dto.response.CustomerSummary;

/**
 * Contract schema {@code Pass}. No price: the amount is on the payment, behind payments:read (NFR-SEC-06).
 * status is the effective status at the request instant; expiresAt null means "no expiry".
 */
public record IssuedPass(String id, long version, CustomerSummary customer, String kind, String productId,
        String productName, String categoryName, ProductTerms terms, Long remainingMinutes, Long remainingCreditKrw,
        String status, Instant startedAt, Instant expiresAt, String purchaseBranchId, String purchasePaymentId,
        Instant createdAt, boolean used, boolean canRefund) {}
