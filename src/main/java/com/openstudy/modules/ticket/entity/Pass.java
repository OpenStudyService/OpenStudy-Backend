package com.openstudy.modules.ticket.entity;

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
 * A pass issued by one approved payment (ADR-M3M4-001 D01: one payment, one pass). The snapshot columns are the
 * purchase-time terms (FR-PASS-03) and never follow later product edits. Customer, branch and payment are plain ids
 * (JPA-03); fk_passes_3 ties them to the same payment row, so a pass cannot mix customers or branches.
 */
@Entity
@Table(name = "passes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Pass {
    private static final String INT_UNSIGNED = "int unsigned";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "customer_id", nullable = false, updatable = false)
    private long customerId;
    @Column(name = "product_id", nullable = false, updatable = false)
    private long productId;
    @Column(name = "purchase_payment_id", nullable = false, updatable = false)
    private long purchasePaymentId;
    @Column(name = "purchase_branch_id", nullable = false, updatable = false)
    private long purchaseBranchId;
    @Column(name = "purchase_product_revision_id", nullable = false, updatable = false)
    private long purchaseProductRevisionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false)
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
    private Long remainingMinutes;
    @Column(name = "remaining_credit_krw")
    private Long remainingCreditKrw;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private PassStatus status;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "expires_at")
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
}
