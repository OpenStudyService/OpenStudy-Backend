package com.openstudy.modules.ticket.entity;

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
 * A pass issued by one approved payment (ADR-M3M4-001 D01: one payment, one pass). The snapshot columns are the
 * purchase-time terms (FR-PASS-03) and never follow later product edits. Customer, branch and payment are plain ids
 * (JPA-03); fk_passes_3 ties them to the same payment row, so a pass cannot mix customers or branches.
 */
@Entity
@Table(name = "passes")
@SemanticEntity(
        id = "pass",
        name = "이용권",
        description = "회원에게 발급된 기간권·시간권·충전권 원장",
        aliases = {"기간권", "시간권", "보유 이용권", "만료 예정 이용권"},
        domain = "passes"
)
@SemanticPolicy(
        readPolicy = "openstudy.read.ai",
        rowPolicy = "openstudy.branchScope",
        fieldPolicy = "openstudy.field.public"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Pass {
    private static final String INT_UNSIGNED = "int unsigned";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @SemanticField(id = "pass.id", name = "이용권 ID", role = "ID", allowedOps = {"EQ", "NE", "IN"})
    private Long id;
    @Column(name = "customer_id", nullable = false, updatable = false)
    @SemanticRelation(id = "pass.customer", targetEntity = "customer", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "이용권 보유 회원")
    private long customerId;
    @Column(name = "product_id", nullable = false, updatable = false)
    @SemanticField(id = "pass.product_id", name = "상품 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "pass.product", targetEntity = "product", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "발급된 이용권 상품")
    private long productId;
    @Column(name = "purchase_payment_id", nullable = false, updatable = false)
    @SemanticField(id = "pass.payment_id", name = "결제 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "pass.payment", targetEntity = "payment", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "이용권 구매 원결제")
    private long purchasePaymentId;
    @Column(name = "purchase_branch_id", nullable = false, updatable = false)
    @SemanticField(id = "pass.branch_id", name = "발급 지점 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "pass.branch", targetEntity = "branch", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "이용권 발급 지점")
    private long purchaseBranchId;
    @Column(name = "purchase_product_revision_id", nullable = false, updatable = false)
    private long purchaseProductRevisionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false)
    @SemanticField(id = "pass.pass_type", name = "이용권 구분", role = "CATEGORY", allowedValues = {"TIME", "PERIOD", "STORED_VALUE", "HOURS", "DAYS"}, aliases = {"시간권", "기간권"}, allowedOps = {"EQ", "NE", "IN"})
    private ProductKind kind;
    @Column(name = "snapshot_price_krw", nullable = false, updatable = false)
    private long snapshotPriceKrw;
    @Column(name = "snapshot_product_name", nullable = false, length = 120, updatable = false)
    private String snapshotProductName;
    @Column(name = "snapshot_category_code", nullable = false, length = 40, updatable = false)
    private String snapshotCategoryCode;
    @Column(name = "snapshot_category_name", nullable = false, length = 100, updatable = false)
    private String snapshotCategoryName;
    @Column(name = "snapshot_minutes", columnDefinition = INT_UNSIGNED, updatable = false)
    private Long snapshotMinutes;
    @Column(name = "snapshot_period_days", columnDefinition = INT_UNSIGNED, updatable = false)
    private Long snapshotPeriodDays;
    @Column(name = "snapshot_credit_krw", updatable = false)
    private Long snapshotCreditKrw;
    @Column(name = "snapshot_hourly_rate_krw", updatable = false)
    private Long snapshotHourlyRateKrw;
    @Column(name = "snapshot_valid_days", columnDefinition = INT_UNSIGNED, updatable = false)
    private Long snapshotValidDays;
    @Column(name = "remaining_minutes", columnDefinition = INT_UNSIGNED)
    @SemanticField(id = "pass.remaining_minutes", name = "잔여 시간(분)", role = "MEASURE", unit = "minute", allowedOps = {"EQ", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private Long remainingMinutes;
    @Column(name = "remaining_credit_krw")
    private Long remainingCreditKrw;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    @SemanticField(id = "pass.status", name = "이용권 상태", role = "STATUS", allowedValues = {"ACTIVE", "EXHAUSTED", "EXPIRED", "REFUNDED", "NOT_STARTED"}, allowedOps = {"EQ", "NE", "IN"})
    private PassStatus status;
    @Column(name = "started_at")
    @SemanticField(id = "pass.issued_at", name = "발급 시각", role = "TIME", aliases = {"발행일", "시작일"}, allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private Instant startedAt;
    @Column(name = "expires_at")
    @SemanticField(id = "pass.expires_at", name = "만료 시각", role = "TIME", aliases = {"만료일", "유효기간 만료"}, allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN"})
    private Instant expiresAt;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    @Version
    private long version;

    /** Purchase-time product terms; only the columns of the pass kind are set, as ck_passes_1 requires. */
    public record Snapshot(ProductKind kind, long priceKrw, String productName, String categoryCode, String categoryName,
            Long minutes, Long periodDays, Long creditKrw, Long hourlyRateKrw, Long validDays) {}

    /**
     * Every kind starts ACTIVE at the issue instant (M4-D01). A period pass ends after periodDays × 24h (M4-D02);
     * time and stored-value passes after validDays × 24h, or never when there is no valid-days term (M4-D03).
     */
    public static Pass issue(long customerId, long productId, long paymentId, long branchId, long revisionId,
            Snapshot terms, Instant issuedAt) {
        var pass = new Pass();
        pass.customerId = customerId; pass.productId = productId; pass.purchasePaymentId = paymentId;
        pass.purchaseBranchId = branchId; pass.purchaseProductRevisionId = revisionId; pass.kind = terms.kind();
        pass.snapshotPriceKrw = terms.priceKrw(); pass.snapshotProductName = terms.productName();
        pass.snapshotCategoryCode = terms.categoryCode(); pass.snapshotCategoryName = terms.categoryName();
        pass.snapshotMinutes = terms.minutes(); pass.snapshotPeriodDays = terms.periodDays();
        pass.snapshotCreditKrw = terms.creditKrw(); pass.snapshotHourlyRateKrw = terms.hourlyRateKrw();
        pass.snapshotValidDays = terms.validDays();
        pass.remainingMinutes = terms.minutes(); pass.remainingCreditKrw = terms.creditKrw();
        pass.status = PassStatus.ACTIVE;
        pass.startedAt = issuedAt;
        Long days = terms.kind() == ProductKind.PERIOD ? terms.periodDays() : terms.validDays();
        pass.expiresAt = days == null ? null : issuedAt.plus(java.time.Duration.ofHours(24 * days));
        return pass;
    }

    /**
     * Applies a settled seat session. A balance never goes below zero, and a used-up time or stored-value pass becomes
     * EXHAUSTED; a period pass has no balance and is only ever charged zero.
     */
    public void deduct(long minutes, long krw) {
        if (minutes < 0 || krw < 0) throw new IllegalArgumentException("A deduction is not negative");
        if (kind == ProductKind.TIME) {
            if (minutes > remainingMinutes || krw != 0) throw new IllegalArgumentException("Deduction exceeds the balance");
            remainingMinutes -= minutes;
        } else if (kind == ProductKind.STORED_VALUE) {
            if (krw > remainingCreditKrw || minutes != 0) throw new IllegalArgumentException("Deduction exceeds the balance");
            remainingCreditKrw -= krw;
        } else if (minutes + krw != 0) {
            throw new IllegalArgumentException("A period pass has no balance");
        }
        if (status == PassStatus.ACTIVE && balanceUsedUp()) status = PassStatus.EXHAUSTED;
    }

    /**
     * A manual correction (M5-D04), already validated against the pass kind. The balance decides ACTIVE or EXHAUSTED, so
     * an adjustment can revive a used-up pass and can use one up. Expiry itself stays a matter of the clock; only a row
     * stored as EXPIRED (a V1 enum value nothing writes today) has to leave that state once its expiry is lifted.
     */
    public void adjust(Long minutes, Long creditKrw, Instant expiresAt, Instant now) {
        if (status == PassStatus.REFUNDED || status == PassStatus.NOT_STARTED)
            throw new IllegalStateException("Pass in status " + status + " cannot be adjusted");
        remainingMinutes = minutes; remainingCreditKrw = creditKrw; this.expiresAt = expiresAt;
        boolean storedExpiryStands = status == PassStatus.EXPIRED && expiresAt != null && !now.isBefore(expiresAt);
        if (!storedExpiryStands) status = balanceUsedUp() ? PassStatus.EXHAUSTED : PassStatus.ACTIVE;
    }

    /** Only time and stored-value passes have a balance; a period pass is never used up. */
    public boolean balanceUsedUp() {
        return switch (kind) {
            case TIME -> remainingMinutes == 0;
            case STORED_VALUE -> remainingCreditKrw == 0;
            case PERIOD -> false;
        };
    }

    /** Started and expiry times are kept as issued; only the status moves (contract "환불"). */
    public void refund() {
        if (status == PassStatus.REFUNDED) throw new IllegalStateException("Pass already refunded");
        status = PassStatus.REFUNDED;
    }
}
