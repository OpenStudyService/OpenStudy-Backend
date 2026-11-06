package com.openstudy.modules.payment.entity;

import java.time.Instant;

import com.openstudy.domainlens.api.semantic.SemanticEntity;
import com.openstudy.domainlens.api.semantic.SemanticField;
import com.openstudy.domainlens.api.semantic.SemanticPolicy;
import com.openstudy.domainlens.api.semantic.SemanticRelation;
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
@SemanticEntity(
        id = "refund",
        name = "환불",
        description = "결제에 대한 완료 환불 내역",
        aliases = {"환불액", "결제취소", "환불 차감"},
        domain = "sales"
)
@SemanticPolicy(
        readPolicy = "openstudy.read.sales",
        rowPolicy = "openstudy.branchScope",
        fieldPolicy = "openstudy.field.public"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Refund {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @SemanticField(id = "refund.id", name = "환불 ID", role = "ID", allowedOps = {"EQ", "NE", "IN"})
    private Long id;
    @Column(name = "payment_id", nullable = false, updatable = false)
    @SemanticField(id = "refund.payment_id", name = "원결제 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "refund.payment", targetEntity = "payment", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "환불 대상 원결제")
    private long paymentId;
    @Column(name = "processing_branch_id", nullable = false, updatable = false)
    private long processingBranchId;
    @Column(name = "processed_by", nullable = false, updatable = false)
    private long processedBy;
    @Column(name = "amount_krw", nullable = false, updatable = false)
    @SemanticField(id = "refund.amount_krw", name = "환불 금액", role = "AMOUNT", unit = "KRW", aliases = {"환불액", "차감액"}, allowedOps = {"EQ", "GT", "GTE", "LT", "LTE", "BETWEEN", "SUM", "AVG", "MIN", "MAX"})
    private long amountKrw;
    @Column(nullable = false, length = 500, updatable = false)
    @SemanticField(id = "refund.reason", name = "환불 사유", exposed = false)
    private String reason;
    @Column(name = "idempotency_key", nullable = false, length = 120, updatable = false)
    private String idempotencyKey;
    @Column(name = "refunded_at", nullable = false, updatable = false)
    @SemanticField(id = "refund.refunded_at", name = "환불 시각", role = "TIME", aliases = {"환불일", "환불처리일"}, allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN"})
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
