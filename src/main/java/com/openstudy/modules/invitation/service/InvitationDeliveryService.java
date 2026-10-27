package com.openstudy.modules.invitation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.openstudy.modules.invitation.repository.InvitationDeliveryRepository;
import com.openstudy.modules.invitation.repository.InvitationDeliveryRepository.InvitationState;
import com.openstudy.modules.invitation.repository.InvitationDeliveryRepository.Job;

/** Small committed state transitions; SMTP is never invoked by this service or under these locks. */
@Service
public class InvitationDeliveryService {
    private final InvitationDeliveryRepository repository;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final InvitationDeliverySettings settings;
    private final SecureRandom random = new SecureRandom();

    public InvitationDeliveryService(InvitationDeliveryRepository repository, PlatformTransactionManager transactions,
            Clock clock, InvitationDeliverySettings settings) {
        this.repository = repository;
        this.clock = clock;
        this.settings = settings;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Optional<DeliveryAttempt> claimNext(String workerId) {
        if (workerId == null || !workerId.matches("[A-Za-z0-9_.:-]{1,100}")) {
            throw new IllegalArgumentException("Invalid invitation worker identifier");
        }
        // A transaction owns just one invitation at a time. The candidate scan itself is non-locking.
        for (var candidate : repository.candidates(now(), settings.batchSize() * 4)) {
            Optional<DeliveryAttempt> attempt = transaction.execute(status -> {
                repository.lockGuard();
                var invitation = repository.invitationForUpdate(candidate.invitationId());
                if (invitation.isEmpty()) return Optional.empty();
                var job = repository.jobForUpdate(candidate.jobId());
                if (job.isEmpty() || job.get().invitationId() != invitation.get().id()) return Optional.empty();
                return claim(invitation.get(), job.get(), workerId, now());
            });
            if (attempt != null && attempt.isPresent()) return attempt;
        }
        return Optional.empty();
    }

    /** Last short committed check before external handoff; cancellation may still race the network. */
    public boolean stillCurrent(DeliveryAttempt attempt) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            repository.lockGuard();
            var invitation = repository.invitationForUpdate(attempt.invitationId());
            if (invitation.isEmpty()) return false;
            var job = repository.jobForUpdate(attempt.jobId());
            if (job.isEmpty() || !identity(invitation.get(), job.get(), attempt)) return false;
            Instant now = now();
            if (!deliverable(invitation.get(), job.get(), now)) return false;
            if (!liveLease(job.get(), now)) {
                retry(invitation.get(), job.get(), now, "LEASE_EXPIRED", false);
                return false;
            }
            return MessageDigest.isEqual(invitation.get().tokenHash(), hash(attempt.token()));
        }));
    }

    public boolean succeeded(DeliveryAttempt attempt) { return finish(attempt, true); }
    public boolean failed(DeliveryAttempt attempt) { return finish(attempt, false); }

    private Optional<DeliveryAttempt> claim(InvitationState invitation, Job job, String workerId, Instant now) {
        if (!"PENDING".equals(job.status()) && !"PROCESSING".equals(job.status())) return Optional.empty();
        if (job.revision() != invitation.revision()) {
            repository.skip(job, "SUPERSEDED_REVISION");
            return Optional.empty();
        }
        if (!deliverable(invitation, job, now)) return Optional.empty();
        if ("PROCESSING".equals(job.status())) {
            if (!liveLease(job, now)) retry(invitation, job, now, "LEASE_EXPIRED", false);
            return Optional.empty();
        }
        if (job.nextAttemptAt() != null && now.isBefore(job.nextAttemptAt())) return Optional.empty();
        // A token already attached to this revision cannot be reconstructed or regenerated in place.
        if (invitation.hasToken()) {
            retry(invitation, job, now, "UNRECOVERABLE_TOKEN", false);
            return Optional.empty();
        }
        if (job.attempts() >= settings.maxAttempts()) {
            repository.exhaust(invitation, job, "ATTEMPTS_EXHAUSTED");
            repository.changed(invitation, job, "MAIL_ATTEMPTS_EXHAUSTED");
            return Optional.empty();
        }
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        Instant leaseUntil = now.plus(settings.leaseDuration()).truncatedTo(ChronoUnit.MICROS);
        if (invitation.expiresAt().isBefore(leaseUntil)) leaseUntil = invitation.expiresAt();
        repository.claim(invitation, job, hash(token), workerId, leaseUntil);
        repository.changed(invitation, job, "MAIL_CLAIMED");
        return Optional.of(new DeliveryAttempt(invitation.id(), job.id(), invitation.revision(), workerId,
                invitation.email(), invitation.name(), token, leaseUntil));
    }

    private boolean finish(DeliveryAttempt attempt, boolean success) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            repository.lockGuard();
            var invitation = repository.invitationForUpdate(attempt.invitationId());
            if (invitation.isEmpty()) return false;
            var job = repository.jobForUpdate(attempt.jobId());
            if (job.isEmpty() || !identity(invitation.get(), job.get(), attempt)) return false;
            Instant now = now();
            if (!deliverable(invitation.get(), job.get(), now)) return false;
            if (!liveLease(job.get(), now)) {
                retry(invitation.get(), job.get(), now, "LEASE_EXPIRED", false);
                return false;
            }
            if (!MessageDigest.isEqual(invitation.get().tokenHash(), hash(attempt.token()))) return false;
            if (success) {
                repository.sent(invitation.get(), job.get(), now);
                repository.changed(invitation.get(), job.get(), "MAIL_SENT");
            } else {
                // Exception messages, transport responses, recipient and token are never persisted.
                retry(invitation.get(), job.get(), now, "SMTP_FAILED", true);
            }
            return true;
        }));
    }

    private boolean deliverable(InvitationState invitation, Job job, Instant now) {
        if (!"PENDING".equals(invitation.status())) {
            repository.skip(job, "INVITATION_CLOSED");
            repository.changed(invitation, job, "MAIL_SKIPPED");
            return false;
        }
        if (!now.isBefore(invitation.expiresAt())) {
            repository.expire(invitation);
            repository.changed(invitation, job, "EXPIRED");
            return false;
        }
        return true;
    }

    private void retry(InvitationState invitation, Job job, Instant now, String reason, boolean failed) {
        if (job.attempts() >= settings.maxAttempts()) {
            repository.exhaust(invitation, job, "ATTEMPTS_EXHAUSTED");
            repository.changed(invitation, job, "MAIL_ATTEMPTS_EXHAUSTED");
            return;
        }
        Instant due = now.plus(settings.retryDelay()).truncatedTo(ChronoUnit.MICROS);
        if (!due.isBefore(invitation.expiresAt())) {
            // No useful retry fits inside the original TTL. Keep a visible terminal delivery failure.
            repository.exhaust(invitation, job, reason);
            repository.changed(invitation, job, "MAIL_RETRY_WINDOW_CLOSED");
            return;
        }
        repository.reserveRetry(invitation, job, due, reason, failed);
        repository.changed(invitation, job, "MAIL_RETRY_RESERVED");
    }

    private static boolean identity(InvitationState invitation, Job job, DeliveryAttempt attempt) {
        return job.invitationId() == invitation.id() && job.id() == attempt.jobId()
                && invitation.revision() == attempt.revision() && job.revision() == attempt.revision()
                && "PROCESSING".equals(job.status()) && attempt.workerId().equals(job.workerId())
                && attempt.leaseUntil().equals(job.leaseUntil());
    }

    private static boolean liveLease(Job job, Instant now) { return job.leaseUntil() != null && now.isBefore(job.leaseUntil()); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static byte[] hash(String token) {
        try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
