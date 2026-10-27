package com.openstudy.modules.invitation.entity;

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
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * Flyway owns the schema; ddl-auto=validate checks this mapping. Mail delivery changes it through JPQL updates in
 * InvitationRepository (phase 6-1); management and acceptance still write through JDBC until phase 6-2.
 * <p>active_invitation_slot is a STORED generated column backing the one-active-invitation UNIQUE key; not mapped.
 * Actor columns are plain employee ids: other modules' entities are never referenced (JPA-03).</p>
 */
@Entity
@Table(name = "employee_invitations")
public class EmployeeInvitation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "branch_id", nullable = false)
    private Long branchId;
    @Column(name = "invitee_email", nullable = false, length = 254)
    private String inviteeEmail;
    @Column(name = "invitee_name", nullable = false, length = 100)
    private String inviteeName;
    @Enumerated(EnumType.STRING) @Column(name = "initial_role", nullable = false)
    private InitialRole initialRole;
    @Column(name = "preset_id")
    private Long presetId;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private InvitationStatus status;
    @JdbcTypeCode(SqlTypes.BINARY) @Column(name = "token_hash", length = 32)
    private byte[] tokenHash;
    @Column(nullable = false)
    private int revision;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "accepted_employee_id")
    private Long acceptedEmployeeId;
    @Column(name = "accepted_at")
    private Instant acceptedAt;
    @Column(name = "approved_by")
    private Long approvedBy;
    @Column(name = "approved_at")
    private Instant approvedAt;
    @Column(name = "rejected_by")
    private Long rejectedBy;
    @Column(name = "rejected_at")
    private Instant rejectedAt;
    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;
    @Column(name = "cancelled_by")
    private Long cancelledBy;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    // Both timestamps are DB-maintained (DEFAULT / ON UPDATE CURRENT_TIMESTAMP(6)).
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    /** Exposed to clients and checked with 409 on every invitation command (JPA-02). */
    @Version
    private long version;
    @Column(name = "created_by", nullable = false)
    private Long createdBy;
    @Column(name = "updated_by")
    private Long updatedBy;

    protected EmployeeInvitation() {}

    public Long getId() { return id; }
    public Long getBranchId() { return branchId; }
    public String getInviteeEmail() { return inviteeEmail; }
    public String getInviteeName() { return inviteeName; }
    public InitialRole getInitialRole() { return initialRole; }
    public Long getPresetId() { return presetId; }
    public InvitationStatus getStatus() { return status; }
    public byte[] getTokenHash() { return tokenHash == null ? null : tokenHash.clone(); }
    public int getRevision() { return revision; }
    public Instant getExpiresAt() { return expiresAt; }
    public Long getAcceptedEmployeeId() { return acceptedEmployeeId; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public Long getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public Long getRejectedBy() { return rejectedBy; }
    public Instant getRejectedAt() { return rejectedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public Long getCancelledBy() { return cancelledBy; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public Long getCreatedBy() { return createdBy; }
    public Long getUpdatedBy() { return updatedBy; }
}
