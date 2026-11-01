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
 * Who looked a customer up, at which branch and when (M5-D07). Append-only. (branch, employee) references
 * branch_memberships, so an OWNER without a membership row at that branch cannot be recorded here.
 */
@Entity
@Table(name = "customer_service_interactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerServiceInteraction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "customer_id", nullable = false, updatable = false)
    private long customerId;
    @Column(name = "branch_id", nullable = false, updatable = false)
    private long branchId;
    @Column(name = "employee_id", nullable = false, updatable = false)
    private long employeeId;
    @Column(nullable = false, length = 500, updatable = false)
    private String reason;
    @Column(name = "verified_at", nullable = false, updatable = false)
    private Instant verifiedAt;

    public CustomerServiceInteraction(long customerId, long branchId, long employeeId, String reason, Instant verifiedAt) {
        this.customerId = customerId; this.branchId = branchId; this.employeeId = employeeId;
        this.reason = reason; this.verifiedAt = verifiedAt;
    }
}
