package com.openstudy.modules.payment.dto.response;

import java.time.Instant;

/** Contract schema {@code Refund}: the single full refund of a payment (FR-PAY-10). */
public record RefundView(String id, long amountKrw, String reason, Instant refundedAt, String processingBranchId,
        ActorSummary processedBy) {}
