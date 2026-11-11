package com.openstudy.modules.retention.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /** The rules this server can run, in the order of the daily job. Undecided kinds (transactions, inquiries,
     * attendance) have no rule at all (ADR-M11-002). */
    public static final List<String> RULES = List.of("CUSTOMER_IDENTITY", "AI_CONVERSATION", "ACCOUNT_MAIL_HISTORY",
            "IDEMPOTENCY_RECORD", "AUDIT_EVENT");

    private final RetentionRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RetentionService(RetentionRepository repository, PlatformTransactionManager transactions, Clock clock) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactions);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** What one run did; {@code ran} is false for a disabled rule, which leaves no record. */
    public record Outcome(String rule, boolean ran, int target, int processed, String errorCode) {}

    public Map<String, Outcome> runAll() {
        var outcomes = new LinkedHashMap<String, Outcome>();
        for (String rule : RULES) outcomes.put(rule, run(rule));
        return outcomes;
    }

    public Outcome run(String code) {
        var rule = repository.rule(code).orElseThrow(() -> new IllegalStateException("Unknown retention rule " + code));
        if (!rule.enabled() || rule.periodDays() == null) return new Outcome(code, false, 0, 0, null);
        // Defense in depth: the CHECK constraint already refuses this, but a rule must never delete inside its legal minimum.
        if (rule.legalMinimumDays() != null && rule.periodDays() < rule.legalMinimumDays()) {
            throw new IllegalStateException("Retention period of " + code + " is shorter than its legal minimum");
        }
        Instant started = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = started.minus(Duration.ofDays(rule.periodDays()));
        try {
            int[] counts = transactions.execute(status -> apply(code, cutoff, started));
            Instant finished = clock.instant();
            transactions.executeWithoutResult(status -> repository.recordRun(code, rule.periodDays(), cutoff, started, finished,
                    counts[0], counts[1], null));
            if (counts[1] > 0) log.info("Retention rule {} processed {} of {}", code, counts[1], counts[0]);
            return new Outcome(code, true, counts[0], counts[1], null);
        } catch (RuntimeException failure) {
            // The type only: a message could quote a value that was being removed.
            String error = failure.getClass().getSimpleName();
            transactions.executeWithoutResult(status -> repository.recordRun(code, rule.periodDays(), cutoff, started,
                    clock.instant(), 0, 0, error));
            log.error("Retention rule {} failed: {}", code, error);
            return new Outcome(code, true, 0, 0, error);
        }
    }

    /** Returns {target, processed}. */
    private int[] apply(String code, Instant cutoff, Instant now) {
        return switch (code) {
            case "CUSTOMER_IDENTITY" -> {
                int target = repository.countInactiveCustomers(cutoff, now);
                yield new int[] {target, untilDone(() -> repository.anonymizeInactiveCustomers(cutoff, now))};
            }
            case "AI_CONVERSATION" -> {
                int target = repository.countIdleConversations(cutoff);
                yield new int[] {target, untilDone(() -> repository.deleteIdleConversations(cutoff))};
            }
            case "ACCOUNT_MAIL_HISTORY" -> {
                int done = repository.deleteEndedResetLinks(cutoff) + untilDone(() -> repository.deleteUnjoinedInvitations(cutoff))
                        + repository.deleteFinishedSendJobs(cutoff);
                yield new int[] {done, done};
            }
            case "IDEMPOTENCY_RECORD" -> {
                int done = untilDone(() -> repository.deleteExpiredIdempotencyRecords(cutoff));
                yield new int[] {done, done};
            }
            case "AUDIT_EVENT" -> {
                int done = untilDone(() -> repository.deleteAuditEventsBefore(cutoff));
                yield new int[] {done, done};
            }
            default -> throw new IllegalStateException("No retention action for " + code);
        };
    }

    private static int untilDone(java.util.function.IntSupplier chunk) {
        int total = 0;
        for (int done = chunk.getAsInt(); done > 0; done = chunk.getAsInt()) total += done;
        return total;
    }
}
