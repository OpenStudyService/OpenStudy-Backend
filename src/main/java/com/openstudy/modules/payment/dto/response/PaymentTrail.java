package com.openstudy.modules.payment.dto.response;

import java.time.Instant;

/** When a payment was approved and refunded and by whom. Deliberately without amounts (NFR-SEC-06). */
public record PaymentTrail(Instant approvedAt, long soldBy, RefundTrail refund) {
    public record RefundTrail(Instant refundedAt, long refundedBy, long branchId, String reason) {}
}
