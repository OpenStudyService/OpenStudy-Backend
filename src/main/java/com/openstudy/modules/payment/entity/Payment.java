package com.openstudy.modules.payment.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * The original Mock transaction of a purchase branch. Amount, product name and approval time are written once and
 * never rewritten, also after a refund (FR-PAY-10). Customer, branch and product are plain ids (JPA-03).
 * ck_payments_issued_pass_quantity ties the issued-pass count to the status.
 */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "customer_id", nullable = false, updatable = false)
    private long customerId;
    @Column(name = "branch_id", nullable = false, updatable = false)
    private long branchId;
    @Column(name = "product_id", updatable = false)
    private Long productId;
    @Column(name = "product_name_snapshot", length = 120, updatable = false)
    private String productNameSnapshot;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false)
    private PaymentPurpose purpose;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false)
    private PaymentMethod method;
    @Column(name = "amount_krw", nullable = false, updatable = false)
    private long amountKrw;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private PaymentStatus status;
    /** Server-made key, unique across every payment; the DB's last guard against a duplicate charge. */
    @Column(name = "idempotency_key", nullable = false, length = 120, updatable = false)
    private String idempotencyKey;
    @Column(name = "issued_pass_quantity", nullable = false, updatable = false, columnDefinition = "smallint unsigned")
    private int issuedPassQuantity;
    @Column(name = "approved_at", updatable = false)
    private Instant approvedAt;
    @Column(name = "failed_at", updatable = false)
    private Instant failedAt;
    @Column(name = "created_by", nullable = false, updatable = false)
    private long createdBy;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    @Version
    private long version;

    /** A pass purchase decided at {@code at}: approved with one pass, or failed with none (FR-PAY-04). */
    public static Payment pass(long customerId, long branchId, long productId, String productName, PaymentMethod method,
            long amountKrw, boolean approved, String idempotencyKey, long actor, Instant at) {
        var payment = new Payment();
        payment.customerId = customerId; payment.branchId = branchId; payment.productId = productId;
        payment.productNameSnapshot = productName; payment.purpose = PaymentPurpose.PASS; payment.method = method;
        payment.amountKrw = amountKrw; payment.idempotencyKey = idempotencyKey; payment.createdBy = actor;
        payment.status = approved ? PaymentStatus.APPROVED : PaymentStatus.FAILED;
        payment.issuedPassQuantity = approved ? 1 : 0;
        if (approved) payment.approvedAt = at; else payment.failedAt = at;
        return payment;
    }
}
