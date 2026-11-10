package com.openstudy.modules.account.service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.modules.account.repository.AccountCredentialRepository;
import com.openstudy.modules.account.repository.AccountCredentialRepository.ResetRow;
import com.openstudy.modules.invitation.service.InvitationDeliverySettings;

/**
 * Small committed steps of reset-mail delivery, following the invitation mail design (invitation-delivery.md): the token
 * is made at claim and only its hash is stored, SMTP runs outside any transaction, and a lost lease or a failed send clears
 * the hash so the next claim makes a new link. The worker locks one reset row at a time and no other row, so it never waits
 * on the command locks. Batch size, lease, retry delay and attempts are the invitation mail settings.
 */
@Service
public class PasswordResetDelivery {
    private final AccountCredentialRepository repository;
    private final AuditTrail audit;
    private final InvitationDeliverySettings settings;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public PasswordResetDelivery(AccountCredentialRepository repository, AuditTrail audit, InvitationDeliverySettings settings,
            Clock clock, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.audit = audit;
        this.settings = settings;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Transient handoff to the mail sender. Never persist, log or serialize it. */
    public record Attempt(long id, long employeeId, String workerId, Instant leaseUntil, String email, String name, String token) {
        @Override public String toString() { return "Attempt[id=" + id + "]"; }
    }

    public Optional<Attempt> claimNext(String workerId) {
        if (workerId == null || !workerId.matches("[A-Za-z0-9_.:-]{1,100}"))
            throw new IllegalArgumentException("Invalid password reset worker identifier");
        for (long id : repository.candidates(now(), settings.batchSize() * 4)) {
            Optional<Attempt> attempt = transaction.execute(status -> repository.lock(id).flatMap(row -> claim(row, workerId)));
            if (attempt != null && attempt.isPresent()) return attempt;
        }
        return Optional.empty();
    }

    public void succeeded(Attempt attempt) { finish(attempt, true); }

    public void failed(Attempt attempt) { finish(attempt, false); }

    private Optional<Attempt> claim(ResetRow row, String workerId) {
        Instant now = now();
        if ("SENDING".equals(row.status())) {
            // The worker that held it is gone; its token cannot be trusted or rebuilt.
            if (row.leaseUntil() != null && !now.isBefore(row.leaseUntil())) retryOrFail(row, now, "LEASE_EXPIRED");
            return Optional.empty();
        }
        if (!"PENDING".equals(row.status())) return Optional.empty();
        var account = repository.account(row.employeeId()).filter(found -> "ACTIVE".equals(found.status()));
        if (account.isEmpty()) {
            repository.close(row.id(), "REVOKED", "EMPLOYEE_NOT_ACTIVE");
            return Optional.empty();
        }
        if (row.attempts() >= settings.maxAttempts()) {
            fail(row, "ATTEMPTS_EXHAUSTED");
            return Optional.empty();
        }
        String token = Passwords.newToken();
        Instant leaseUntil = now.plus(settings.leaseDuration()).truncatedTo(ChronoUnit.MICROS);
        repository.claim(row.id(), Passwords.hash(token), now.plus(AccountCredentialService.LINK_LIFETIME), workerId, leaseUntil);
        return Optional.of(new Attempt(row.id(), row.employeeId(), workerId, leaseUntil, account.get().email(),
                account.get().name(), token));
    }

    private void finish(Attempt attempt, boolean success) {
        transaction.executeWithoutResult(status -> repository.lock(attempt.id()).ifPresent(row -> {
            // Only the claim this attempt made may finish: same worker, same lease, same token, lease still live.
            Instant now = now();
            boolean current = "SENDING".equals(row.status()) && attempt.workerId().equals(row.workerId())
                    && attempt.leaseUntil().equals(row.leaseUntil()) && row.tokenHash() != null
                    && MessageDigest.isEqual(row.tokenHash(), Passwords.hash(attempt.token()));
            if (!current) return;
            if (!now.isBefore(row.leaseUntil())) {
                retryOrFail(row, now, "LEASE_EXPIRED");
            } else if (success) {
                repository.sent(row.id());
                audit.record(AuditEvent.change(null, null, "employee", row.employeeId(), "PASSWORD_RESET_MAIL_SENT", null, null, null));
            } else {
                // Provider messages, recipients and the token are never stored.
                retryOrFail(row, now, "SMTP_FAILED");
            }
        }));
    }

    private void retryOrFail(ResetRow row, Instant now, String reason) {
        if (row.attempts() >= settings.maxAttempts()) {
            fail(row, "ATTEMPTS_EXHAUSTED");
            return;
        }
        repository.retry(row.id(), now.plus(settings.retryDelay()).truncatedTo(ChronoUnit.MICROS), reason);
    }

    /** Delivery gave up: the employee has neither a link nor the old password until an administrator asks again (M14-D11). */
    private void fail(ResetRow row, String reason) {
        repository.close(row.id(), "FAILED", reason);
        audit.record(AuditEvent.change(null, null, "employee", row.employeeId(), "PASSWORD_RESET_MAIL_FAILED", reason, null, null, null));
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
