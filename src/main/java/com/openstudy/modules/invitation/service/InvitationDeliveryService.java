package com.openstudy.modules.invitation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import com.openstudy.modules.invitation.repository.InvitationDeliveryRepository.Candidate;
import com.openstudy.modules.invitation.repository.InvitationDeliveryRepository.InvitationState;
import com.openstudy.modules.invitation.repository.InvitationDeliveryRepository.Job;

/** Small committed state transitions; SMTP is never invoked by this service or under these locks. */
@Service
public class InvitationDeliveryService {

    private static final String WORKER_ID_FORMAT = "[A-Za-z0-9_.:-]{1,100}";
    private static final int CANDIDATES_PER_BATCH_SLOT = 4;
    private static final int TOKEN_BYTES = 32;
    private static final String PENDING = "PENDING";
    private static final String PROCESSING = "PROCESSING";

    private final InvitationDeliveryRepository invitationDeliveryRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final InvitationDeliverySettings invitationDeliverySettings;
    private final SecureRandom secureRandom = new SecureRandom();

    public InvitationDeliveryService(
            InvitationDeliveryRepository invitationDeliveryRepository,
            PlatformTransactionManager transactionManager,
            Clock clock,
            InvitationDeliverySettings invitationDeliverySettings
    ) {
        this.invitationDeliveryRepository = invitationDeliveryRepository;
        this.clock = clock;
        this.invitationDeliverySettings = invitationDeliverySettings;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Optional<DeliveryAttempt> claimNext(String workerId) {
        if (workerId == null || !workerId.matches(WORKER_ID_FORMAT)) {
            throw new IllegalArgumentException("Invalid invitation worker identifier");
        }

        // A transaction owns just one invitation at a time. The candidate scan itself is non-locking.
        int limit = invitationDeliverySettings.batchSize() * CANDIDATES_PER_BATCH_SLOT;

        for (Candidate candidate : invitationDeliveryRepository.candidates(findNow(), limit)) {
            Optional<DeliveryAttempt> attempt = transactionTemplate.execute(
                    status -> claimCandidate(candidate, workerId)
            );

            if (attempt != null && attempt.isPresent()) {
                return attempt;
            }
        }

        return Optional.empty();
    }

    /** Last short committed check before external handoff; cancellation may still race the network. */
    public boolean stillCurrent(DeliveryAttempt attempt) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            invitationDeliveryRepository.lockGuard();
            Optional<InvitationState> invitation =
                    invitationDeliveryRepository.invitationForUpdate(attempt.invitationId());

            if (invitation.isEmpty()) {
                return false;
            }

            Optional<Job> job = invitationDeliveryRepository.jobForUpdate(attempt.jobId());

            if (job.isEmpty() || !matchesAttempt(invitation.get(), job.get(), attempt)) {
                return false;
            }

            return isLiveAttempt(invitation.get(), job.get(), attempt, findNow());
        }));
    }

    public boolean succeeded(DeliveryAttempt attempt) {
        return finish(attempt, true);
    }

    public boolean failed(DeliveryAttempt attempt) {
        return finish(attempt, false);
    }

    private Optional<DeliveryAttempt> claimCandidate(Candidate candidate, String workerId) {
        invitationDeliveryRepository.lockGuard();
        Optional<InvitationState> invitation =
                invitationDeliveryRepository.invitationForUpdate(candidate.invitationId());

        if (invitation.isEmpty()) {
            return Optional.empty();
        }

        Optional<Job> job = invitationDeliveryRepository.jobForUpdate(candidate.jobId());

        if (job.isEmpty() || job.get().invitationId() != invitation.get().id()) {
            return Optional.empty();
        }

        return claim(invitation.get(), job.get(), workerId, findNow());
    }

    private Optional<DeliveryAttempt> claim(InvitationState invitation, Job job, String workerId, Instant now) {
        if (!PENDING.equals(job.status()) && !PROCESSING.equals(job.status())) {
            return Optional.empty();
        }

        if (job.revision() != invitation.revision()) {
            invitationDeliveryRepository.skip(job, "SUPERSEDED_REVISION");
            return Optional.empty();
        }

        if (!isDeliverable(invitation, job, now)) {
            return Optional.empty();
        }

        if (PROCESSING.equals(job.status())) {
            if (!hasLiveLease(job, now)) {
                retry(invitation, job, now, "LEASE_EXPIRED", false);
            }

            return Optional.empty();
        }

        if (job.nextAttemptAt() != null && now.isBefore(job.nextAttemptAt())) {
            return Optional.empty();
        }

        // A token already attached to this revision cannot be reconstructed or regenerated in place.
        if (invitation.hasToken()) {
            retry(invitation, job, now, "UNRECOVERABLE_TOKEN", false);
            return Optional.empty();
        }

        if (job.attempts() >= invitationDeliverySettings.maxAttempts()) {
            exhaustAttempts(invitation, job);
            return Optional.empty();
        }

        String token = createToken();
        Instant leaseUntil = now.plus(invitationDeliverySettings.leaseDuration()).truncatedTo(ChronoUnit.MICROS);

        if (invitation.expiresAt().isBefore(leaseUntil)) {
            leaseUntil = invitation.expiresAt();
        }

        invitationDeliveryRepository.claim(invitation, job, hash(token), workerId, leaseUntil);
        invitationDeliveryRepository.changed(invitation, job, "MAIL_CLAIMED");

        return Optional.of(new DeliveryAttempt(
                invitation.id(),
                job.id(),
                invitation.revision(),
                workerId,
                invitation.email(),
                invitation.name(),
                token,
                leaseUntil
        ));
    }

    private boolean finish(DeliveryAttempt attempt, boolean success) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            invitationDeliveryRepository.lockGuard();
            Optional<InvitationState> invitation =
                    invitationDeliveryRepository.invitationForUpdate(attempt.invitationId());

            if (invitation.isEmpty()) {
                return false;
            }

            Optional<Job> job = invitationDeliveryRepository.jobForUpdate(attempt.jobId());

            if (job.isEmpty() || !matchesAttempt(invitation.get(), job.get(), attempt)) {
                return false;
            }

            Instant now = findNow();

            if (!isLiveAttempt(invitation.get(), job.get(), attempt, now)) {
                return false;
            }

            if (success) {
                invitationDeliveryRepository.sent(invitation.get(), job.get(), now);
                invitationDeliveryRepository.changed(invitation.get(), job.get(), "MAIL_SENT");
            } else {
                // Exception messages, transport responses, recipient and token are never persisted.
                retry(invitation.get(), job.get(), now, "SMTP_FAILED", true);
            }

            return true;
        }));
    }

    /**
     * The checks shared by the pre-send check and the result: still deliverable, lease alive (an expired lease is
     * moved to a retry), and the stored hash is this attempt's token.
     */
    private boolean isLiveAttempt(InvitationState invitation, Job job, DeliveryAttempt attempt, Instant now) {
        if (!isDeliverable(invitation, job, now)) {
            return false;
        }

        if (!hasLiveLease(job, now)) {
            retry(invitation, job, now, "LEASE_EXPIRED", false);
            return false;
        }

        return MessageDigest.isEqual(invitation.tokenHash(), hash(attempt.token()));
    }

    private boolean isDeliverable(InvitationState invitation, Job job, Instant now) {
        if (!PENDING.equals(invitation.status())) {
            invitationDeliveryRepository.skip(job, "INVITATION_CLOSED");
            invitationDeliveryRepository.changed(invitation, job, "MAIL_SKIPPED");
            return false;
        }

        if (!now.isBefore(invitation.expiresAt())) {
            invitationDeliveryRepository.expire(invitation);
            invitationDeliveryRepository.changed(invitation, job, "EXPIRED");
            return false;
        }

        return true;
    }

    private void retry(InvitationState invitation, Job job, Instant now, String reason, boolean failed) {
        if (job.attempts() >= invitationDeliverySettings.maxAttempts()) {
            exhaustAttempts(invitation, job);
            return;
        }

        Instant due = now.plus(invitationDeliverySettings.retryDelay()).truncatedTo(ChronoUnit.MICROS);

        if (!due.isBefore(invitation.expiresAt())) {
            // No useful retry fits inside the original TTL. Keep a visible terminal delivery failure.
            invitationDeliveryRepository.exhaust(invitation, job, reason);
            invitationDeliveryRepository.changed(invitation, job, "MAIL_RETRY_WINDOW_CLOSED");
            return;
        }

        invitationDeliveryRepository.reserveRetry(invitation, job, due, reason, failed);
        invitationDeliveryRepository.changed(invitation, job, "MAIL_RETRY_RESERVED");
    }

    private void exhaustAttempts(InvitationState invitation, Job job) {
        invitationDeliveryRepository.exhaust(invitation, job, "ATTEMPTS_EXHAUSTED");
        invitationDeliveryRepository.changed(invitation, job, "MAIL_ATTEMPTS_EXHAUSTED");
    }

    private String createToken() {
        byte[] entropy = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(entropy);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    private Instant findNow() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean matchesAttempt(InvitationState invitation, Job job, DeliveryAttempt attempt) {
        return job.invitationId() == invitation.id()
                && job.id() == attempt.jobId()
                && invitation.revision() == attempt.revision()
                && job.revision() == attempt.revision()
                && PROCESSING.equals(job.status())
                && attempt.workerId().equals(job.workerId())
                && attempt.leaseUntil().equals(job.leaseUntil());
    }

    private static boolean hasLiveLease(Job job, Instant now) {
        return job.leaseUntil() != null && now.isBefore(job.leaseUntil());
    }

    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
