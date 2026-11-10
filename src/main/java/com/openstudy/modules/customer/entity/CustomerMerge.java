package com.openstudy.modules.customer.entity;

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
 * One absorbed customer of one merge (V14, M13-D03): the target, the absorbed customer, the customer it had been merged
 * into before when a later merge moved it ({@code via}), who and when, and why. Rows are only inserted.
 */
@Entity
@Table(name = "customer_merges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerMerge {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "target_customer_id", nullable = false, updatable = false)
    private long targetCustomerId;
    @Column(name = "absorbed_customer_id", nullable = false, updatable = false)
    private long absorbedCustomerId;
    @Column(name = "via_customer_id", updatable = false)
    private Long viaCustomerId;
    @Column(name = "merged_by", nullable = false, updatable = false)
    private long mergedBy;
    @Column(name = "merged_at", nullable = false, updatable = false)
    private Instant mergedAt;
    @Column(nullable = false, length = 500, updatable = false)
    private String reason;

    public CustomerMerge(long target, long absorbed, Long via, long mergedBy, Instant mergedAt, String reason) {
        this.targetCustomerId = target; this.absorbedCustomerId = absorbed; this.viaCustomerId = via;
        this.mergedBy = mergedBy; this.mergedAt = mergedAt; this.reason = reason;
    }
}
