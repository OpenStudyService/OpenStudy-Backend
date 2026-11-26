package com.openstudy.modules.invitation.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.modules.invitation.entity.EmployeeInvitation;
import com.openstudy.modules.invitation.entity.InvitationSendJob;
import com.openstudy.modules.invitation.entity.SendJobStatus;

/**
 * Worker persistence; guarded transitions lock the invitation parent before its job.
 * <p>Reads take entity row locks and hand the service value snapshots, exactly as the former JDBC reads did; every
 * transition is a JPQL update that mirrors the former statement, so versions advance exactly as before.</p>
 */
@Repository
@RequiredArgsConstructor
public class InvitationDeliveryRepository {

    private static final String AUDIT_ENTITY_TYPE = "invitations";

    private final InvitationRepository invitationRepository;
    private final InvitationSendJobRepository invitationSendJobRepository;
    private final ObjectMapper objectMapper;
    private final AuditTrail auditTrail;

    public record Candidate(long invitationId, long jobId) {
    }

    public record InvitationState(
            long id,
            long branchId,
            String status,
            long revision,
            Instant expiresAt,
            String email,
            String name,
            byte[] tokenHash
    ) {

        public boolean hasToken() {
            return tokenHash != null;
        }

        @Override
        public String toString() {
            return "InvitationState[id=" + id + ",revision=" + revision + "]";
        }
    }

    public record Job(
            long id,
            long invitationId,
            long revision,
            String status,
            int attempts,
            Instant nextAttemptAt,
            Instant leaseUntil,
            String workerId
    ) {
    }

    public void lockGuard() {
        if (invitationSendJobRepository.findGuardForUpdate().isEmpty()) {
            throw new IllegalStateException("administration_guard row is missing");
        }
    }

    /** Non-locking bounded scan: do not acquire a job lock before its parent invitation. */
    public List<Candidate> candidates(Instant now, int limit) {
        return invitationSendJobRepository.findCandidates(now, Limit.of(limit))
                .stream()
                .map(row -> new Candidate(((Number) row[0]).longValue(), ((Number) row[1]).longValue()))
                .toList();
    }

    public Optional<InvitationState> invitationForUpdate(long id) {
        return invitationRepository.findForUpdateById(id).map(InvitationDeliveryRepository::toState);
    }

    public Optional<Job> jobForUpdate(long id) {
        return invitationSendJobRepository.findForUpdateById(id).map(InvitationDeliveryRepository::toJob);
    }

    public void claim(InvitationState invitation, Job job, byte[] hash, String workerId, Instant leaseUntil) {
        invitationRepository.attachToken(invitation.id(), hash);
        invitationSendJobRepository.claim(job.id(), workerId, leaseUntil);
    }

    public void sent(InvitationState invitation, Job job, Instant now) {
        invitationSendJobRepository.markSent(job.id(), now);
        invitationRepository.markDelivered(invitation.id());
    }

    public void reserveRetry(InvitationState invitation, Job job, Instant due, String reason, boolean failed) {
        SendJobStatus finished = failed ? SendJobStatus.FAILED : SendJobStatus.SKIPPED;
        invitationSendJobRepository.finish(job.id(), finished, reason);
        invitationRepository.advanceRevision(invitation.id());

        // Attempts is cumulative within this automatic chain. Manual reissue creates attempts=0.
        int nextRevision = Math.toIntExact(invitation.revision() + 1);
        invitationSendJobRepository.saveAndFlush(
                InvitationSendJob.retry(invitation.id(), nextRevision, job.attempts(), due)
        );
    }

    public void exhaust(InvitationState invitation, Job job, String reason) {
        invitationSendJobRepository.finish(job.id(), SendJobStatus.FAILED, reason);
        invitationRepository.dropToken(invitation.id());
    }

    public void skip(Job job, String reason) {
        invitationSendJobRepository.finish(job.id(), SendJobStatus.SKIPPED, reason);
    }

    public void expire(InvitationState invitation) {
        invitationRepository.expire(invitation.id());
        invitationSendJobRepository.skipOpen(invitation.id());
    }

    /** Operational evidence has IDs and fixed state codes only; no recipient, token or token hash. */
    public void changed(InvitationState invitation, Job job, String action) {
        Map<String, Object> snapshot = Map.of("jobId", Long.toString(job.id()), "revision", job.revision());
        AuditEvent event = AuditEvent.change(
                null,
                invitation.branchId(),
                AUDIT_ENTITY_TYPE,
                invitation.id(),
                action,
                null,
                objectMapper.writeValueAsString(snapshot),
                null
        );

        auditTrail.record(event);
    }

    private static InvitationState toState(EmployeeInvitation invitation) {
        return new InvitationState(
                invitation.getId(),
                invitation.getBranchId(),
                invitation.getStatus().name(),
                invitation.getRevision(),
                invitation.getExpiresAt(),
                invitation.getInviteeEmail(),
                invitation.getInviteeName(),
                invitation.getTokenHash()
        );
    }

    private static Job toJob(InvitationSendJob job) {
        return new Job(
                job.getId(),
                job.getInvitationId(),
                job.getRevision(),
                job.getStatus().name(),
                job.getAttempts(),
                job.getNextAttemptAt(),
                job.getLockedUntil(),
                job.getWorkerId()
        );
    }
}
