package com.openstudy.modules.payment.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The single full refund of a payment, kept as its own event so the original payment row is never rewritten
 * (FR-PAY-10). uk_refunds_1 allows one refund per payment and uk_refunds_2 one per server-made key: the DB's last
 * guard against a double refund. The processing branch is the payment branch (M4-D08).
 */
@Entity
@Table(name = "refunds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "payment_id", nullable = false, updatable = false)
    private long paymentId;
    @Column(name = "processing_branch_id", nullable = false, updatable = false)
    private long processingBranchId;
    @Column(name = "processed_by", nullable = false, updatable = false)
    private long processedBy;
    @Column(name = "amount_krw", nullable = false, updatable = false)
    private long amountKrw;
    @Column(nullable = false, length = 500, updatable = false)
    private String reason;
    @Column(name = "idempotency_key", nullable = false, length = 120, updatable = false)
    private String idempotencyKey;
    @Column(name = "refunded_at", nullable = false, updatable = false)
    private Instant refundedAt;

    /** The whole amount of {@code payment}, processed at its own branch. */
    public static Refund full(Payment payment, long actor, String reason, String idempotencyKey, Instant at) {
        var refund = new Refund();
        refund.paymentId = payment.getId(); refund.processingBranchId = payment.getBranchId(); refund.processedBy = actor;
        refund.amountKrw = payment.getAmountKrw(); refund.reason = reason; refund.idempotencyKey = idempotencyKey;
        refund.refundedAt = at;
        return refund;
    }
}
