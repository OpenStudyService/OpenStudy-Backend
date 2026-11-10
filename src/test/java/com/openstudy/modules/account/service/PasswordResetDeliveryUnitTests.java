package com.openstudy.modules.account.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.modules.account.adapter.SmtpPasswordResetMailSender;
import com.openstudy.modules.account.repository.AccountCredentialRepository;
import com.openstudy.modules.account.repository.AccountCredentialRepository.Account;
import com.openstudy.modules.account.repository.AccountCredentialRepository.ResetRow;
import com.openstudy.modules.invitation.service.InvitationDeliverySettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #119 reset-mail delivery without a database or SMTP: the token is made at claim and stored as a hash with a one-hour life,
 * only the claim that made it may finish it, failures retry without the old token, and giving up is audited. The SMTP
 * adapter accepts only a trusted reset URL.
 */
class PasswordResetDeliveryUnitTests {
    static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    static final InvitationDeliverySettings SETTINGS = new InvitationDeliverySettings(5, Duration.ofSeconds(60), Duration.ofSeconds(30), 3);

    final AccountCredentialRepository repository = mock(AccountCredentialRepository.class);
    final AuditTrail audit = mock(AuditTrail.class);
    final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    final PasswordResetDelivery delivery;

    PasswordResetDeliveryUnitTests() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        delivery = new PasswordResetDelivery(repository, audit, SETTINGS, Clock.fixed(NOW, ZoneOffset.UTC), transactions);
    }

    static ResetRow row(String status, int attempts, byte[] hash, String worker, Instant lease) {
        return new ResetRow(7, 5, status, hash, hash == null ? null : NOW.plusSeconds(3600), attempts, worker, lease);
    }

    void pending(int attempts, String accountStatus) {
        when(repository.candidates(any(), anyInt())).thenReturn(List.of(7L));
        when(repository.lock(7)).thenReturn(Optional.of(row("PENDING", attempts, null, null, null)));
        when(repository.account(5)).thenReturn(Optional.of(new Account(5, "user@example.com", "직원", accountStatus, false, "h")));
    }

    @Test void aClaimMakesATokenAndStoresOnlyItsHashForOneHour() {
        pending(0, "ACTIVE");
        var attempt = delivery.claimNext("worker-1").orElseThrow();
        assertThat(Passwords.wellFormedToken(attempt.token())).isTrue();
        assertThat(attempt.toString()).doesNotContain(attempt.token(), "user@example.com");
        var hash = ArgumentCaptor.forClass(byte[].class);
        verify(repository).claim(eq(7L), hash.capture(), eq(NOW.plus(Duration.ofHours(1))), eq("worker-1"), eq(NOW.plusSeconds(60)));
        assertThat(hash.getValue()).isEqualTo(Passwords.hash(attempt.token()));
    }

    @Test void anInactiveAccountIsRevokedAndExhaustedAttemptsFail() {
        pending(0, "LEFT");
        assertThat(delivery.claimNext("w")).isEmpty();
        verify(repository).close(7, "REVOKED", "EMPLOYEE_NOT_ACTIVE");

        pending(3, "ACTIVE");
        assertThat(delivery.claimNext("w")).isEmpty();
        verify(repository).close(7, "FAILED", "ATTEMPTS_EXHAUSTED");
        var event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(event.capture());
        assertThat(event.getValue().getAction()).isEqualTo("PASSWORD_RESET_MAIL_FAILED");
        assertThat(event.getValue().getEntityId()).isEqualTo(5L);
    }

    @Test void aLostLeaseGoesBackToPendingWithoutItsToken() {
        when(repository.candidates(any(), anyInt())).thenReturn(List.of(7L));
        when(repository.lock(7)).thenReturn(Optional.of(row("SENDING", 1, new byte[32], "gone", NOW.minusSeconds(1))));
        assertThat(delivery.claimNext("w")).isEmpty();
        verify(repository).retry(7, NOW.plusSeconds(30), "LEASE_EXPIRED");
    }

    PasswordResetDelivery.Attempt claimed() {
        pending(0, "ACTIVE");
        return delivery.claimNext("worker-1").orElseThrow();
    }

    void current(PasswordResetDelivery.Attempt attempt, int attempts) {
        when(repository.lock(7)).thenReturn(Optional.of(row("SENDING", attempts, Passwords.hash(attempt.token()), "worker-1",
                attempt.leaseUntil())));
    }

    @Test void onlyTheClaimThatMadeTheTokenMarksItSent() {
        var attempt = claimed();
        current(attempt, 1);
        delivery.succeeded(attempt);
        verify(repository).sent(7);

        // Replaced meanwhile by a new request: nothing happens.
        when(repository.lock(7)).thenReturn(Optional.of(row("REVOKED", 1, null, null, null)));
        delivery.succeeded(attempt);
        verify(repository).sent(anyLong());
    }

    @Test void aFailedSendRetriesUntilTheAttemptsRunOut() {
        var attempt = claimed();
        current(attempt, 1);
        delivery.failed(attempt);
        verify(repository).retry(7, NOW.plusSeconds(30), "SMTP_FAILED");
        current(attempt, 3);
        delivery.failed(attempt);
        verify(repository).close(7, "FAILED", "ATTEMPTS_EXHAUSTED");
        verify(repository, never()).sent(anyLong());
    }

    @Test void theWorkerSendsEachClaimAndRecordsTheOutcome() {
        var sent = new ArrayList<String>();
        var worker = new PasswordResetWorker(delivery, (email, name, token) -> sent.add(email), SETTINGS);
        pending(0, "ACTIVE");
        // The second claim finds nothing more to do.
        when(repository.candidates(any(), anyInt())).thenReturn(List.of(7L)).thenReturn(List.of());
        when(repository.lock(7)).thenReturn(Optional.of(row("PENDING", 0, null, null, null)));
        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(sent).containsExactly("user@example.com");
    }

    @Test void theResetUrlMustBeTrusted() {
        var smtp = new org.springframework.mail.javamail.JavaMailSenderImpl();
        new SmtpPasswordResetMailSender(smtp, "noreply@example.com", "https://app.example.com/password-reset");
        new SmtpPasswordResetMailSender(smtp, "noreply@example.com", "http://localhost:5173/password-reset");
        for (String bad : List.of("http://app.example.com/password-reset", "https://app.example.com/reset",
                "https://app.example.com/password-reset?x=1", "https://app.example.com/password-reset#token=1",
                "https://user@app.example.com/password-reset", "")) {
            assertThatThrownBy(() -> new SmtpPasswordResetMailSender(smtp, "noreply@example.com", bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new SmtpPasswordResetMailSender(smtp, "Name <noreply@example.com>", "https://a.example.com/password-reset"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
