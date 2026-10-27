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
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * One mail delivery attempt chain for one invitation revision. State transitions are JPQL updates in
 * InvitationSendJobRepository that mirror the former SQL; the entity itself is only inserted and read.
 */
@Entity
@Table(name = "invitation_send_jobs")
public class InvitationSendJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "invitation_id", nullable = false, updatable = false)
    private Long invitationId;
    @Column(nullable = false, updatable = false)
    private int revision;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private SendJobStatus status;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;
    @Column(name = "sent_at")
    private Instant sentAt;
    @Column(name = "last_error_code", length = 80)
    private String lastErrorCode;
    // Both timestamps are DB-maintained (DEFAULT / ON UPDATE CURRENT_TIMESTAMP(6)).
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    @Column(name = "locked_until")
    private Instant lockedUntil;
    @Column(name = "worker_id", length = 100)
    private String workerId;

    protected InvitationSendJob() {}

    /** A PENDING job for the next revision; attempts carry over within an automatic retry chain. */
    public static InvitationSendJob retry(long invitationId, int revision, int attempts, Instant nextAttemptAt) {
        var job = new InvitationSendJob();
        job.invitationId = invitationId; job.revision = revision; job.status = SendJobStatus.PENDING;
        job.attempts = attempts; job.nextAttemptAt = nextAttemptAt;
        return job;
    }

    public Long getId() { return id; }
    public Long getInvitationId() { return invitationId; }
    public int getRevision() { return revision; }
    public SendJobStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getSentAt() { return sentAt; }
    public String getLastErrorCode() { return lastErrorCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getLockedUntil() { return lockedUntil; }
    public String getWorkerId() { return workerId; }
}
