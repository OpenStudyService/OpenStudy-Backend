package com.openstudy.modules.account.service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.modules.account.repository.AccountCredentialRepository;
import com.openstudy.modules.account.repository.AccountCredentialRepository.Account;
import com.openstudy.modules.account.repository.AccountCredentialRepository.ResetRow;
import com.openstudy.modules.invitation.service.InvitationDeliverySettings;

/**
 * Small committed steps of reset-mail delivery, following the invitation mail design (invitation-delivery.md).
 *
 * <p>The token is made at claim and only its hash is stored, SMTP runs outside any transaction,
 * and a lost lease or a failed send clears the hash so the next claim makes a new link.
 * The worker locks one reset row at a time and no other row, so it never waits on the command locks.
 * Batch size, lease, retry delay and attempts are the invitation mail settings.
 */
@Service
public class PasswordResetDelivery {

    private static final String ENTITY = "employee";
    private static final Pattern WORKER_ID_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{1,100}");

    /** How many candidate rows to look at per batch slot; some may be taken by another worker meanwhile. */
    private static final int CANDIDATES_PER_BATCH_SLOT = 4;

    private final AccountCredentialRepository accountCredentialRepository;
    private final AuditTrail auditTrail;
    private final InvitationDeliverySettings settings;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public PasswordResetDelivery(
            AccountCredentialRepository accountCredentialRepository,
            AuditTrail auditTrail,
            InvitationDeliverySettings settings,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.accountCredentialRepository = accountCredentialRepository;
        this.auditTrail = auditTrail;
        this.settings = settings;
        this.clock = clock;

        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Transient handoff to the mail sender. Never persist, log or serialize it. */
    public record Attempt(
            long id,
            long employeeId,
            String workerId,
            Instant leaseUntil,
            String email,
            String name,
            String token
    ) {
        @Override
        public String toString() {
            return "Attempt[id=" + id + "]";
        }
    }

    public Optional<Attempt> claimNext(String workerId) {
        if (workerId == null || !WORKER_ID_PATTERN.matcher(workerId).matches()) {
            throw new IllegalArgumentException("Invalid password reset worker identifier");
        }

        int candidateLimit = settings.batchSize() * CANDIDATES_PER_BATCH_SLOT;

        for (long id : accountCredentialRepository.findCandidateIds(now(), candidateLimit)) {
            Optional<Attempt> attempt = transactionTemplate.execute(status ->
                    accountCredentialRepository.lock(id).flatMap(row -> claim(row, workerId))
            );

            if (attempt != null && attempt.isPresent()) {
                return attempt;
            }
        }

        return Optional.empty();
    }

    public void succeeded(Attempt attempt) {
        finish(attempt, true);
    }

    public void failed(Attempt attempt) {
        finish(attempt, false);
    }

    private Optional<Attempt> claim(ResetRow row, String workerId) {
        Instant now = now();

        if ("SENDING".equals(row.status())) {
            // The worker that held it is gone; its token cannot be trusted or rebuilt.
            boolean leaseExpired = row.leaseUntil() != null && !now.isBefore(row.leaseUntil());
            if (leaseExpired) {
                retryOrFail(row, now, "LEASE_EXPIRED");
            }
            return Optional.empty();
        }

        if (!"PENDING".equals(row.status())) {
            return Optional.empty();
        }

        Optional<Account> account = accountCredentialRepository.findAccount(row.employeeId())
                .filter(found -> "ACTIVE".equals(found.status()));

        if (account.isEmpty()) {
            accountCredentialRepository.close(row.id(), "REVOKED", "EMPLOYEE_NOT_ACTIVE");
            return Optional.empty();
        }

        if (row.attempts() >= settings.maxAttempts()) {
            fail(row, "ATTEMPTS_EXHAUSTED");
            return Optional.empty();
        }

        String token = Passwords.createToken();
        Instant expiresAt = now.plus(AccountCredentialService.LINK_LIFETIME);
        Instant leaseUntil = now.plus(settings.leaseDuration()).truncatedTo(ChronoUnit.MICROS);

        accountCredentialRepository.claim(row.id(), Passwords.hashToken(token), expiresAt, workerId, leaseUntil);

        return Optional.of(new Attempt(
                row.id(),
                row.employeeId(),
                workerId,
                leaseUntil,
                account.get().email(),
                account.get().name(),
                token
        ));
    }

    private void finish(Attempt attempt, boolean success) {
        transactionTemplate.executeWithoutResult(status ->
                accountCredentialRepository.lock(attempt.id())
                        .ifPresent(row -> finishLockedRow(row, attempt, success))
        );
    }

    private void finishLockedRow(ResetRow row, Attempt attempt, boolean success) {
        // Only the claim this attempt made may finish: same worker, same lease, same token, lease still live.
        Instant now = now();

        if (!isClaimedBy(row, attempt)) {
            return;
        }

        if (!now.isBefore(row.leaseUntil())) {
            retryOrFail(row, now, "LEASE_EXPIRED");
        } else if (success) {
            accountCredentialRepository.markSent(row.id());
            auditTrail.record(AuditEvent.change(
                    null,
                    null,
                    ENTITY,
                    row.employeeId(),
                    "PASSWORD_RESET_MAIL_SENT",
                    null,
                    null,
                    null
            ));
        } else {
            // Provider messages, recipients and the token are never stored.
            retryOrFail(row, now, "SMTP_FAILED");
        }
    }

    private static boolean isClaimedBy(ResetRow row, Attempt attempt) {
        return "SENDING".equals(row.status())
                && attempt.workerId().equals(row.workerId())
                && attempt.leaseUntil().equals(row.leaseUntil())
                && row.tokenHash() != null
                && MessageDigest.isEqual(row.tokenHash(), Passwords.hashToken(attempt.token()));
    }

    private void retryOrFail(ResetRow row, Instant now, String reason) {
        if (row.attempts() >= settings.maxAttempts()) {
            fail(row, "ATTEMPTS_EXHAUSTED");
            return;
        }

        Instant due = now.plus(settings.retryDelay()).truncatedTo(ChronoUnit.MICROS);
        accountCredentialRepository.scheduleRetry(row.id(), due, reason);
    }

    /** Delivery gave up: the employee has neither a link nor the old password until an administrator asks again (M14-D11). */
    private void fail(ResetRow row, String reason) {
        accountCredentialRepository.close(row.id(), "FAILED", reason);
        auditTrail.record(AuditEvent.change(
                null,
                null,
                ENTITY,
                row.employeeId(),
                "PASSWORD_RESET_MAIL_FAILED",
                reason,
                null,
                null,
                null
        ));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
