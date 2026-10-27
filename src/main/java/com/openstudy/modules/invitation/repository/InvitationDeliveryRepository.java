package com.openstudy.modules.invitation.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.outbox.WorkspaceOutbox;
import com.openstudy.modules.invitation.entity.InvitationSendJob;
import com.openstudy.modules.invitation.entity.SendJobStatus;

/**
 * Worker persistence; guarded transitions lock the invitation parent before its job.
 * <p>Reads take entity row locks and hand the service value snapshots, exactly as the former JDBC reads did; every
 * transition is a JPQL update that mirrors the former statement, so versions advance exactly as before.</p>
 */
@Repository
public class InvitationDeliveryRepository {
    private final InvitationRepository invitations;
    private final InvitationSendJobRepository jobs;
    private final ObjectMapper json;
    private final AuditTrail audit;
    private final WorkspaceOutbox outbox;

    public InvitationDeliveryRepository(InvitationRepository invitations, InvitationSendJobRepository jobs, ObjectMapper json,
            AuditTrail audit, WorkspaceOutbox outbox) {
        this.invitations = invitations; this.jobs = jobs; this.json = json; this.audit = audit; this.outbox = outbox;
    }

    public record Candidate(long invitationId, long jobId) {}
    public record InvitationState(long id, long branchId, String status, long revision, Instant expiresAt,
            String email, String name, byte[] tokenHash) {
        public boolean hasToken() { return tokenHash != null; }
        @Override public String toString() { return "InvitationState[id=" + id + ",revision=" + revision + "]"; }
    }
    public record Job(long id, long invitationId, long revision, String status, int attempts,
            Instant nextAttemptAt, Instant leaseUntil, String workerId) {}

    public void lockGuard() {
        if (jobs.lockGuard().isEmpty()) throw new IllegalStateException("administration_guard row is missing");
    }

    /** Non-locking bounded scan: do not acquire a job lock before its parent invitation. */
    public List<Candidate> candidates(Instant now, int limit) {
        return jobs.candidates(now, Limit.of(limit)).stream()
                .map(row -> new Candidate(((Number) row[0]).longValue(), ((Number) row[1]).longValue())).toList();
    }

    public Optional<InvitationState> invitationForUpdate(long id) {
        return invitations.findForUpdateById(id).map(i -> new InvitationState(i.getId(), i.getBranchId(), i.getStatus().name(),
                i.getRevision(), i.getExpiresAt(), i.getInviteeEmail(), i.getInviteeName(), i.getTokenHash()));
    }

    public Optional<Job> jobForUpdate(long id) {
        return jobs.findForUpdateById(id).map(j -> new Job(j.getId(), j.getInvitationId(), j.getRevision(), j.getStatus().name(),
                j.getAttempts(), j.getNextAttemptAt(), j.getLockedUntil(), j.getWorkerId()));
    }

    public void claim(InvitationState invitation, Job job, byte[] hash, String workerId, Instant leaseUntil) {
        invitations.attachToken(invitation.id(), hash);
        jobs.claim(job.id(), workerId, leaseUntil);
    }

    public void sent(InvitationState invitation, Job job, Instant now) {
        jobs.sent(job.id(), now);
        invitations.markDelivered(invitation.id());
    }

    public void reserveRetry(InvitationState invitation, Job job, Instant due, String reason, boolean failed) {
        jobs.finish(job.id(), failed ? SendJobStatus.FAILED : SendJobStatus.SKIPPED, reason);
        invitations.advanceRevision(invitation.id());
        // Attempts is cumulative within this automatic chain. Manual reissue creates attempts=0.
        jobs.saveAndFlush(InvitationSendJob.retry(invitation.id(), Math.toIntExact(invitation.revision() + 1), job.attempts(), due));
    }

    public void exhaust(InvitationState invitation, Job job, String reason) {
        jobs.finish(job.id(), SendJobStatus.FAILED, reason);
        invitations.dropToken(invitation.id());
    }

    public void skip(Job job, String reason) { jobs.finish(job.id(), SendJobStatus.SKIPPED, reason); }

    public void expire(InvitationState invitation) {
        invitations.expire(invitation.id());
        jobs.skipOpen(invitation.id());
    }

    /** Operational evidence has IDs and fixed state codes only; no recipient, token or token hash. */
    public void changed(InvitationState invitation, Job job, String action) {
        String snapshot = json.writeValueAsString(Map.of("jobId", Long.toString(job.id()), "revision", job.revision()));
        audit.record(AuditEvent.change(null, invitation.branchId(), "invitations", invitation.id(), action, null, snapshot, null));
        outbox.invalidate(invitation.branchId(), "invitations", invitation.id());
    }
}
