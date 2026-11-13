package com.openstudy.modules.payment.entity;

import java.time.Instant;

import com.openstudy.domainlens.api.semantic.SemanticEntity;
import com.openstudy.domainlens.api.semantic.SemanticField;
import com.openstudy.domainlens.api.semantic.SemanticPolicy;
import com.openstudy.domainlens.api.semantic.SemanticRelation;
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
@SemanticEntity(
        id = "payment",
        name = "결제",
        description = "이용권·스터디룸·사물함 원거래 결제 내역",
        aliases = {"매출", "결제내역", "수납", "승인 매출"},
        domain = "sales"
)
@SemanticPolicy(
        readPolicy = "openstudy.read.sales",
        rowPolicy = "openstudy.branchScope",
        fieldPolicy = "openstudy.field.public"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @SemanticField(id = "payment.id", name = "결제 ID", role = "ID", allowedOps = {"EQ", "NE", "IN"})
    private Long id;
    @Column(name = "customer_id", nullable = false, updatable = false)
    @SemanticRelation(id = "payment.customer", targetEntity = "customer", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "결제한 회원")
    private long customerId;
    @Column(name = "branch_id", nullable = false, updatable = false)
    @SemanticField(id = "payment.branch_id", name = "결제 지점 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "payment.branch", targetEntity = "branch", targetField = "branch.id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "결제가 발생한 지점")
    private long branchId;
    @Column(name = "product_id", updatable = false)
    private Long productId;
    @Column(name = "product_name_snapshot", length = 120, updatable = false)
    private String productNameSnapshot;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false)
    @SemanticField(id = "payment.item_type", name = "상품 구분", role = "CATEGORY", allowedValues = {"PASS", "ROOM", "LOCKER"}, aliases = {"이용권", "스터디룸", "사물함", "매출원"}, allowedOps = {"EQ", "NE", "IN"})
    private PaymentPurpose purpose;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false)
    private PaymentMethod method;
    @Column(name = "amount_krw", nullable = false, updatable = false)
    @SemanticField(id = "payment.amount_krw", name = "결제 금액", role = "AMOUNT", unit = "KRW", aliases = {"매출액", "결제액", "승인 매출"}, allowedOps = {"EQ", "GT", "GTE", "LT", "LTE", "BETWEEN", "SUM", "AVG", "MIN", "MAX"})
    private long amountKrw;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    @SemanticField(id = "payment.status", name = "결제 상태", role = "STATUS", allowedValues = {"PENDING", "APPROVED", "FAILED", "REFUNDED"}, allowedOps = {"EQ", "NE", "IN"})
    private PaymentStatus status;
    /** Server-made key, unique across every payment; the DB's last guard against a duplicate charge. */
    @Column(name = "idempotency_key", nullable = false, length = 120, updatable = false)
    private String idempotencyKey;
    @Column(name = "issued_pass_quantity", nullable = false, updatable = false, columnDefinition = "smallint unsigned")
    private int issuedPassQuantity;
    @Column(name = "approved_at", updatable = false)
    // #232: sales periods follow the approval time (created_at is when the attempt was recorded).
    @SemanticField(id = "payment.paid_at", name = "결제 시각", role = "TIME", aliases = {"결제일", "매출일"}, allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN"})
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

    /** A room prepayment decided at {@code at}. It has no product (ck_payments_1) and never issues a pass. */
    public static Payment room(long customerId, long branchId, PaymentMethod method, long amountKrw, boolean approved,
            String idempotencyKey, long actor, Instant at) {
        return space(PaymentPurpose.ROOM, customerId, branchId, method, amountKrw, approved, idempotencyKey, actor, at);
    }

    /** A locker assignment or extension decided at {@code at}. Like a room, it has no product and never issues a pass. */
    public static Payment locker(long customerId, long branchId, PaymentMethod method, long amountKrw, boolean approved,
            String idempotencyKey, long actor, Instant at) {
        return space(PaymentPurpose.LOCKER, customerId, branchId, method, amountKrw, approved, idempotencyKey, actor, at);
    }

    private static Payment space(PaymentPurpose purpose, long customerId, long branchId, PaymentMethod method, long amountKrw,
            boolean approved, String idempotencyKey, long actor, Instant at) {
        var payment = new Payment();
        payment.customerId = customerId; payment.branchId = branchId; payment.purpose = purpose;
        payment.method = method; payment.amountKrw = amountKrw; payment.idempotencyKey = idempotencyKey;
        payment.createdBy = actor; payment.status = approved ? PaymentStatus.APPROVED : PaymentStatus.FAILED;
        if (approved) payment.approvedAt = at; else payment.failedAt = at;
        return payment;
    }

    /** Only the status moves; amount and approval time stay as approved (FR-PAY-10). */
    public void refund() {
        if (status != PaymentStatus.APPROVED) throw new IllegalStateException("Only an approved payment can be refunded");
        status = PaymentStatus.REFUNDED;
    }
}
