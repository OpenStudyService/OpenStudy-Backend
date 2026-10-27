package com.openstudy.modules.authorization.entity;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.generator.EventType;

/**
 * An employee's role and status in one branch, owned by the authorization module (JPA-03). Employee and branch are
 * plain ids: other modules' entities are never referenced.
 * <p>Grant changes are excluded from versioning; MembershipService advances the version exactly once per command,
 * as the former SQL did, so a field change plus a grant change never counts twice.</p>
 */
@Entity
@Table(name = "branch_memberships")
public class BranchMembership {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "employee_id", nullable = false, updatable = false)
    private Long employeeId;
    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private BranchRole role;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private MembershipStatus status;
    @Column(name = "approved_by")
    private Long approvedBy;
    @Column(name = "approved_at")
    private Instant approvedAt;
    // Both timestamps are DB-maintained (DEFAULT / ON UPDATE CURRENT_TIMESTAMP(6)).
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    /** Exposed to clients and checked with 409 on membership changes (JPA-02). */
    @Version
    private long version;
    @Column(name = "created_by", updatable = false)
    private Long createdBy;
    @Column(name = "updated_by")
    private Long updatedBy;
    @ManyToMany
    @JoinTable(name = "membership_permission_grants", joinColumns = @JoinColumn(name = "membership_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    @OptimisticLock(excluded = true)
    private Set<PermissionDefinition> permissions = new LinkedHashSet<>();

    protected BranchMembership() {}

    /** A new pending membership; it carries no effective grants until approval. */
    public static BranchMembership pending(long employeeId, long branchId, BranchRole role, long actor) {
        var membership = new BranchMembership();
        membership.employeeId = employeeId; membership.branchId = branchId; membership.role = role;
        membership.status = MembershipStatus.PENDING; membership.createdBy = actor; membership.updatedBy = actor;
        return membership;
    }

    /** Q11: a revoked membership returns to pending, dropping its former approval and grants. */
    public void returnToPending(BranchRole role, long actor) {
        if (status != MembershipStatus.REVOKED) throw new IllegalStateException("Only a revoked membership can return to pending");
        this.role = role; this.status = MembershipStatus.PENDING; this.approvedBy = null; this.approvedAt = null;
        this.updatedBy = actor;
        permissions.clear();
    }

    public void revoke(long actor) { this.status = MembershipStatus.REVOKED; this.updatedBy = actor; }

    public void change(BranchRole role, MembershipStatus status, long actor) {
        this.role = role; this.status = status; this.updatedBy = actor;
    }

    public void replacePermissions(Set<PermissionDefinition> granted) {
        permissions.retainAll(granted);
        permissions.addAll(granted);
    }

    public Long getId() { return id; }
    public Long getEmployeeId() { return employeeId; }
    public Long getBranchId() { return branchId; }
    public BranchRole getRole() { return role; }
    public MembershipStatus getStatus() { return status; }
    public Long getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public Long getCreatedBy() { return createdBy; }
    public Long getUpdatedBy() { return updatedBy; }
    /** Permission codes in ascending order. */
    public List<String> getPermissionCodes() {
        return permissions.stream().map(PermissionDefinition::getCode).sorted(Comparator.naturalOrder()).toList();
    }
}
