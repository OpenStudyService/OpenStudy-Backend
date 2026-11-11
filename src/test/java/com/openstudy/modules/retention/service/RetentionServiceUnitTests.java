package com.openstudy.modules.retention.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.openstudy.modules.retention.repository.RetentionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** #100: a failed rule is recorded with its error type only, and a rule never runs inside its legal minimum. */
class RetentionServiceUnitTests {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC);

    final RetentionRepository repository = mock(RetentionRepository.class);
    final RetentionService service;

    RetentionServiceUnitTests() {
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new RetentionService(repository, transactions, CLOCK);
    }

    @Test void aFailureIsRecordedWithItsTypeAndNeverItsMessage() {
        when(repository.rule("CUSTOMER_IDENTITY")).thenReturn(Optional.of(new RetentionRepository.Rule("CUSTOMER_IDENTITY", true, 365, null)));
        when(repository.countInactiveCustomers(any(), any())).thenThrow(new IllegalStateException("홍길동 010-1234-5678"));
        var outcome = service.run("CUSTOMER_IDENTITY");
        assertThat(outcome.ran()).isTrue();
        assertThat(outcome.errorCode()).isEqualTo("IllegalStateException");
        verify(repository).recordRun(eq("CUSTOMER_IDENTITY"), eq(365), eq(Instant.parse("2025-11-11T00:00:00Z")), any(), any(),
                eq(0), eq(0), eq("IllegalStateException"));
    }

    @Test void aDisabledRuleOrOneWithoutAPeriodDoesNothingAndRecordsNothing() {
        when(repository.rule("AI_CONVERSATION")).thenReturn(Optional.of(new RetentionRepository.Rule("AI_CONVERSATION", false, 365, null)));
        when(repository.rule("AUDIT_EVENT")).thenReturn(Optional.of(new RetentionRepository.Rule("AUDIT_EVENT", true, null, 1095)));
        assertThat(service.run("AI_CONVERSATION").ran()).isFalse();
        assertThat(service.run("AUDIT_EVENT").ran()).isFalse();
        verify(repository, never()).recordRun(any(), anyInt(), any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test void aPeriodShorterThanTheLegalMinimumIsRefusedBeforeAnythingIsDeleted() {
        when(repository.rule("AUDIT_EVENT")).thenReturn(Optional.of(new RetentionRepository.Rule("AUDIT_EVENT", true, 30, 1095)));
        assertThatThrownBy(() -> service.run("AUDIT_EVENT")).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).deleteAuditEventsBefore(any());
    }
}
