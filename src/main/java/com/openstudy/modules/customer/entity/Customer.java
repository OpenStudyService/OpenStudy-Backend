package com.openstudy.modules.customer.entity;

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
 * Company-wide customer ledger, separate from employee accounts. The registration branch is a plain id (JPA-03); it is
 * NULL only for rows saved before V2 without a payment, and never changes once set. There is no delete: a customer is
 * deactivated and every payment, pass and visit row stays.
 */
@Entity
@Table(name = "customers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Customer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "member_no", nullable = false, length = 40, updatable = false)
    private String memberNo;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(length = 30)
    private String phone;
    @Column(length = 254)
    private String email;
    @Column(name = "registered_branch_id", updatable = false)
    private Long registeredBranchId;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private CustomerStatus status;
    @Column(name = "status_reason", length = 500)
    private String statusReason;
    @Column(length = 1000)
    private String memo;
    // Both timestamps are DB-maintained (DEFAULT / ON UPDATE CURRENT_TIMESTAMP(6)).
    @Generated(event = EventType.INSERT) @Column(name = "registered_at", insertable = false, updatable = false)
    private Instant registeredAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    /** Exposed to clients and checked with 409 on every customer command (JPA-02). */
    @Version
    private long version;

    public Customer(String memberNo, long registeredBranchId, String name, String phone, String email, String memo) {
        this.memberNo = memberNo; this.registeredBranchId = registeredBranchId; this.status = CustomerStatus.NORMAL;
        change(name, phone, email, memo);
    }

    public void change(String name, String phone, String email, String memo) {
        this.name = name; this.phone = phone; this.email = email; this.memo = memo;
    }

    public void changeStatus(CustomerStatus status, String reason) {
        this.status = status; this.statusReason = reason;
    }
}
