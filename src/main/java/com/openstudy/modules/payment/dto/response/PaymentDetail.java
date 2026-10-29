package com.openstudy.modules.payment.dto.response;

import java.time.Instant;
import com.openstudy.modules.customer.dto.response.CustomerSummary;

/**
 * Contract schema {@code Payment}. mock is always true: no money moves (FR-PAY-01). An approved payment has passId and
 * approvedAt, a failed one failedAt and no pass. canRefund is a display hint; the refund command re-checks everything.
 */
public record PaymentDetail(String id, long version, boolean mock, String purpose, String branchId, CustomerSummary customer,
        String productId, String productName, String method, long amountKrw, String status, Instant approvedAt,
        Instant failedAt, Instant createdAt, ActorSummary createdBy, String passId, RefundView refund, boolean canRefund) {}
