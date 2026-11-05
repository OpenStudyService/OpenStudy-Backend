package com.openstudy.modules.sales.dto.response;

import java.time.Instant;

/**
 * Contract schema {@code SalesLedgerEntry} (FR-AGG-18). The component list is the whole wire shape: no customer name,
 * phone or ID, no payment method, no actor and no refund reason (M9-D07). An approval is +amount, a refund −amount.
 */
public record SalesLedgerEntry(String id, String entryType, String paymentId, String refundId, String branchId,
        String processingBranchId, String purpose, String productId, String productName, String categoryCode,
        String categoryName, int passQuantity, long amountKrw, Instant approvedAt, Instant refundedAt, Instant basisAt,
        boolean canViewPayment) {}
