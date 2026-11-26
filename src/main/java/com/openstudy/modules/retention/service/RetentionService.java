package com.openstudy.modules.retention.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.openstudy.modules.retention.repository.RetentionRepository;

/**
 * Runs the retention rules (#100, ADR-M11-001 M11-D01·D05, ADR-M11-002). A rule does nothing until an operator enables it
 * with a period (the database refuses one without, or one shorter than the legal minimum). Each enabled rule runs in its
 * own transaction and leaves one run record of counts only; a failure is recorded in a separate transaction.
 */
@Slf4j
@Service
public class RetentionService {

    /**
     * The rules this server can run, in the order of the daily job. Undecided kinds (transactions, inquiries,
     * attendance) have no rule at all (ADR-M11-002).
     */
    public static final List<String> RULES = List.of(
            "CUSTOMER_IDENTITY",
            "AI_CONVERSATION",
            "ACCOUNT_MAIL_HISTORY",
            "IDEMPOTENCY_RECORD",
            "AUDIT_EVENT"
    );

    private final RetentionRepository retentionRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public RetentionService(
            RetentionRepository retentionRepository,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.retentionRepository = retentionRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** What one run did; {@code ran} is false for a disabled rule, which leaves no record. */
    public record Outcome(String rule, boolean ran, int target, int processed, String errorCode) {
    }

    /** A rule's counts: how many rows qualified and how many were processed. */
    private record Counts(int target, int processed) {
    }

    public Map<String, Outcome> runAll() {
        Map<String, Outcome> outcomes = new LinkedHashMap<>();

        for (String rule : RULES) {
            outcomes.put(rule, run(rule));
        }

        return outcomes;
    }

    public Outcome run(String code) {
        RetentionRepository.Rule rule = retentionRepository.findRule(code)
                .orElseThrow(() -> new IllegalStateException("Unknown retention rule " + code));

        if (!rule.enabled() || rule.periodDays() == null) {
            return new Outcome(code, false, 0, 0, null);
        }

        // Defense in depth: the CHECK constraint already refuses this, but a rule must never delete inside its legal minimum.
        boolean shorterThanLegalMinimum = rule.legalMinimumDays() != null
                && rule.periodDays() < rule.legalMinimumDays();

        if (shorterThanLegalMinimum) {
            throw new IllegalStateException("Retention period of " + code + " is shorter than its legal minimum");
        }

        Instant started = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = started.minus(Duration.ofDays(rule.periodDays()));

        try {
            Counts counts = transactionTemplate.execute(status -> applyRule(code, cutoff, started));
            Instant finished = clock.instant();
            recordRun(code, rule, cutoff, started, finished, counts, null);

            if (counts.processed() > 0) {
                log.info("Retention rule {} processed {} of {}", code, counts.processed(), counts.target());
            }

            return new Outcome(code, true, counts.target(), counts.processed(), null);
        } catch (RuntimeException failure) {
            // The type only: a message could quote a value that was being removed.
            String error = failure.getClass().getSimpleName();
            recordRun(code, rule, cutoff, started, clock.instant(), new Counts(0, 0), error);
            log.error("Retention rule {} failed: {}", code, error);

            return new Outcome(code, true, 0, 0, error);
        }
    }

    /** Records the run in a transaction of its own, so a failure is kept even though the cleanup rolled back. */
    private void recordRun(
            String code,
            RetentionRepository.Rule rule,
            Instant cutoff,
            Instant started,
            Instant finished,
            Counts counts,
            String errorCode
    ) {
        transactionTemplate.executeWithoutResult(status -> retentionRepository.recordRun(
                code,
                rule.periodDays(),
                cutoff,
                started,
                finished,
                counts.target(),
                counts.processed(),
                errorCode
        ));
    }

    private Counts applyRule(String code, Instant cutoff, Instant now) {
        return switch (code) {
            case "CUSTOMER_IDENTITY" -> {
                int target = retentionRepository.countInactiveCustomers(cutoff, now);
                int processed = sumUntilDone(() -> retentionRepository.anonymizeInactiveCustomers(cutoff, now));
                yield new Counts(target, processed);
            }
            case "AI_CONVERSATION" -> {
                int target = retentionRepository.countIdleConversations(cutoff);
                int processed = sumUntilDone(() -> retentionRepository.deleteIdleConversations(cutoff));
                yield new Counts(target, processed);
            }
            case "ACCOUNT_MAIL_HISTORY" -> {
                int done = retentionRepository.deleteEndedResetLinks(cutoff)
                        + sumUntilDone(() -> retentionRepository.deleteUnjoinedInvitations(cutoff))
                        + retentionRepository.deleteFinishedSendJobs(cutoff);
                yield new Counts(done, done);
            }
            case "IDEMPOTENCY_RECORD" -> {
                int done = sumUntilDone(() -> retentionRepository.deleteExpiredIdempotencyRecords(cutoff));
                yield new Counts(done, done);
            }
            case "AUDIT_EVENT" -> {
                int done = sumUntilDone(() -> retentionRepository.deleteAuditEventsBefore(cutoff));
                yield new Counts(done, done);
            }
            default -> throw new IllegalStateException("No retention action for " + code);
        };
    }

    /** Runs one chunk after another until a chunk changes nothing, and returns the total. */
    private static int sumUntilDone(IntSupplier chunk) {
        int total = 0;

        for (int done = chunk.getAsInt(); done > 0; done = chunk.getAsInt()) {
            total += done;
        }

        return total;
    }
}
