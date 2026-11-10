package com.openstudy.modules.customer.entity;

import java.time.Instant;

import com.openstudy.domainlens.api.semantic.SemanticEntity;
import com.openstudy.domainlens.api.semantic.SemanticField;
import com.openstudy.domainlens.api.semantic.SemanticPolicy;
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
@SemanticEntity(
        id = "customer",
        name = "회원",
        description = "OpenStudy 회원 원장 (개인식별정보 보호 대상)",
        domain = "passes",
        exposed = false
)
@SemanticPolicy(
        readPolicy = "openstudy.read.pii.denied",
        fieldPolicy = "openstudy.field.pii.denied"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Customer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @SemanticField(id = "customer.id", name = "회원 ID", role = "ID", exposed = false)
    private Long id;
    @Column(name = "member_no", nullable = false, length = 40, updatable = false)
    @SemanticField(id = "customer.member_no", name = "회원 번호", role = "CODE", exposed = false)
    private String memberNo;
    @Column(nullable = false, length = 100)
    @SemanticField(id = "customer.name", name = "회원 성명", exposed = false)
    private String name;
    @Column(length = 30)
    @SemanticField(id = "customer.phone", name = "회원 전화번호", exposed = false)
    private String phone;
    @Column(length = 254)
    @SemanticField(id = "customer.email", name = "회원 이메일", exposed = false)
    private String email;
    @Column(name = "registered_branch_id", updatable = false)
    private Long registeredBranchId;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private CustomerStatus status;
    @Column(name = "status_reason", length = 500)
    private String statusReason;
    /** The customer this one was merged into (V14, M13-D03); null unless MERGED. */
    @Column(name = "merged_into_customer_id")
    private Long mergedIntoCustomerId;
    @Column(length = 1000)
    @SemanticField(id = "customer.memo", name = "회원 메모", exposed = false)
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

    /** Absorbed into another customer, or moved to a new target when that customer is absorbed in turn (M13-D03). */
    public void mergeInto(long target) {
        this.status = CustomerStatus.MERGED; this.mergedIntoCustomerId = target;
    }
}
