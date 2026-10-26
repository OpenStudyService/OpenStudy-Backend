package com.openstudy.modules.invitation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.modules.invitation.repository.InvitationDeliveryRepository;
import com.openstudy.modules.invitation.service.DeliveryAttempt;
import com.openstudy.modules.invitation.service.InvitationDeliveryService;
import com.openstudy.modules.invitation.service.InvitationDeliverySettings;
import com.openstudy.modules.invitation.service.InvitationEmailWorker;
import com.openstudy.modules.invitation.service.InvitationMailSender;
import com.openstudy.modules.invitation.service.InvitationManagementService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.*;

@MySqlIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class InvitationEmailWorkerIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired InvitationDeliveryRepository repository;
    @Autowired InvitationManagementService management;
    @Autowired ObjectMapper json;
    private long owner, branch;
    private MutableClock clock;
    private InvitationDeliverySettings settings;
    private InvitationDeliveryService delivery;
    private RecordingSender sender;
    private InvitationEmailWorker worker;

    @BeforeEach void fixture() {
        String email = UUID.randomUUID() + "@worker.test";
        jdbc.update("INSERT INTO employees(email,password_hash,name,nickname,global_role,status) VALUES (?,'unused-hash','발송 검증','발송 검증','OWNER','ACTIVE')", email);
        owner = count("SELECT id FROM employees WHERE email=?", email);
        String code = UUID.randomUUID().toString().substring(0, 20);
        jdbc.update("INSERT INTO branches(code,name,address) VALUES (?,'메일 검증 지점','서울')", code);
        branch = count("SELECT id FROM branches WHERE code=?", code);
        // Existing creation reserves jobs using DB UTC time. Start after that time without sleeping.
        clock = new MutableClock(Instant.now().plusSeconds(60));
        settings = new InvitationDeliverySettings(1, Duration.ofSeconds(30), Duration.ofSeconds(10), 3);
        delivery = new InvitationDeliveryService(repository, transactionManager, clock, settings);
        sender = new RecordingSender();
        worker = new InvitationEmailWorker(delivery, sender, settings);
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM invitation_permission_grants WHERE invitation_id IN (SELECT id FROM employee_invitations WHERE branch_id=?)", branch);
        jdbc.update("DELETE FROM invitation_send_jobs WHERE invitation_id IN (SELECT id FROM employee_invitations WHERE branch_id=?)", branch);
        jdbc.update("DELETE FROM employee_invitations WHERE branch_id=?", branch);
        jdbc.update("DELETE FROM audit_events WHERE branch_id=? OR actor_employee_id=?", branch, owner);
        jdbc.update("DELETE FROM workspace_event_outbox WHERE branch_id=?", branch);
        jdbc.update("DELETE FROM idempotency_records WHERE actor_employee_id=?", owner);
        jdbc.update("DELETE FROM branch_memberships WHERE branch_id=?", branch);
        jdbc.update("DELETE FROM employees WHERE id=?", owner);
        jdbc.update("DELETE FROM branches WHERE id=?", branch);
    }

    private long create() {
        return Long.parseLong(management.create(owner, "all", Map.of("branchId", Long.toString(branch),
                "name", "메일 받는 직원", "email", UUID.randomUUID() + "@invite.test", "role", "staff", "permissions", List.of()),
                UUID.randomUUID().toString(), "worker-create-" + UUID.randomUUID()).id());
    }
    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private String state(long id) { return jdbc.queryForObject("SELECT status FROM employee_invitations WHERE id=?", String.class, id); }
    private long revision(long id) { return count("SELECT revision FROM employee_invitations WHERE id=?", id); }
    private Instant expires(long id) { return jdbc.queryForObject("SELECT expires_at FROM employee_invitations WHERE id=?", (rs, n) -> rs.getTimestamp(1).toInstant(), id); }
    private byte[] hash(long id) { return jdbc.queryForObject("SELECT token_hash FROM employee_invitations WHERE id=?", (rs, n) -> rs.getBytes(1), id); }
    private static byte[] digest(String token) throws Exception { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)); }
    private DeliveryAttempt claim(String name) { return delivery.claimNext(name).orElseThrow(); }
    private long version(long id) { return count("SELECT version FROM employee_invitations WHERE id=?", id) + 1; }
    private void noRawTokenPersisted(long id, String token) {
        assertThat(jdbc.queryForList("SELECT * FROM employee_invitations WHERE id=?", id).toString()).doesNotContain(token);
        assertThat(jdbc.queryForList("SELECT * FROM invitation_send_jobs WHERE invitation_id=?", id).toString()).doesNotContain(token);
        assertThat(jdbc.queryForList("SELECT before_json,after_json,request_id FROM audit_events WHERE branch_id=?", branch).toString()).doesNotContain(token);
        assertThat(jdbc.queryForList("SELECT * FROM workspace_event_outbox WHERE branch_id=?", branch).toString()).doesNotContain(token);
        assertThat(jdbc.queryForList("SELECT response_json FROM idempotency_records WHERE actor_employee_id=?", owner).toString()).doesNotContain(token);
    }

    private static final class MutableClock extends Clock {
        private volatile Instant current;
        MutableClock(Instant initial) { current = initial; }
        void advance(Duration duration) { current = current.plus(duration); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return current; }
    }
    private record Mail(String email, String name, String token) {
        @Override public String toString() { return "Mail[redacted]"; }
    }
    private static final class RecordingSender implements InvitationMailSender {
        final List<Mail> received = new CopyOnWriteArrayList<>();
        volatile Consumer<Mail> action = mail -> {};
        public void send(String email, String name, String token) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("SMTP executes outside DB transactions").isFalse();
            var mail = new Mail(email, name, token);
            received.add(mail);
            action.accept(mail);
        }
    }

    @Test void initialDeliveryClaimsRevisionOneAndCommitsOnlyHashBeforeSending(CapturedOutput output) throws Exception {
        long id = create();
        Instant expiry = expires(id);
        sender.action = mail -> {
            assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND revision=1 AND status='PROCESSING'", id)).isEqualTo(1);
            assertThat(hash(id)).isNotNull();
            // A separate transaction can lock the invitation during SMTP: no parent lock is retained.
            new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                    jdbc.queryForObject("SELECT id FROM employee_invitations WHERE id=? FOR UPDATE", Long.class, id));
        };
        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(sender.received).hasSize(1);
        var mail = sender.received.getFirst();
        assertThat(mail.token()).matches("[A-Za-z0-9_-]{43,}");
        assertThat(hash(id)).isEqualTo(digest(mail.token()));
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND revision=1 AND status='SENT' AND sent_at IS NOT NULL", id)).isEqualTo(1);
        assertThat(revision(id)).isEqualTo(1);
        assertThat(expires(id)).isEqualTo(expiry);
        assertThat(state(id)).isEqualTo("PENDING");
        noRawTokenPersisted(id, mail.token());
        assertThat(output.getAll()).doesNotContain(mail.token());
        assertThat(worker.runOnce()).isZero();
    }

    @Test void claimContainsRedactedAttemptAndCompletionRequiresMatchingLease() throws Exception {
        long id = create();
        var attempt = claim("worker-one");
        assertThat(attempt.invitationId()).isEqualTo(id);
        assertThat(attempt.revision()).isEqualTo(1);
        assertThat(attempt.workerId()).isEqualTo("worker-one");
        assertThat(attempt.toString()).doesNotContain(attempt.token(), attempt.email(), attempt.name());
        assertThat(hash(id)).isEqualTo(digest(attempt.token()));
        assertThat(delivery.stillCurrent(attempt)).isTrue();
        assertThat(delivery.claimNext("worker-two")).isEmpty();
        clock.advance(settings.leaseDuration().plusSeconds(1));
        assertThat(delivery.stillCurrent(attempt)).isFalse();
        delivery.succeeded(attempt);
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND status='SENT'", id)).isZero();
        noRawTokenPersisted(id, attempt.token());
    }

    @Test void smtpFailureReservesNewRevisionWithoutExtendingOriginalExpiryAndNeverLogsCause(CapturedOutput output) throws Exception {
        long id = create();
        Instant expiry = expires(id);
        sender.action = mail -> { throw new IllegalStateException("secret=" + mail.token(), new IllegalArgumentException(mail.email() + mail.token())); };
        assertThat(worker.runOnce()).isEqualTo(1);
        String oldToken = sender.received.getFirst().token();
        assertThat(hash(id)).isNull();
        assertThat(revision(id)).isEqualTo(2);
        assertThat(expires(id)).isEqualTo(expiry);
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND revision=1 AND status='FAILED' AND next_attempt_at IS NULL", id)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND revision=2 AND status='PENDING'", id)).isEqualTo(1);
        noRawTokenPersisted(id, oldToken);
        assertThat(output.getAll()).doesNotContain(oldToken, "secret=" + oldToken, sender.received.getFirst().email());
        assertThat(worker.runOnce()).isZero();
        clock.advance(settings.retryDelay().plusSeconds(1));
        sender.action = mail -> {};
        assertThat(worker.runOnce()).isEqualTo(1);
        String retryToken = sender.received.getLast().token();
        assertThat(retryToken).isNotEqualTo(oldToken);
        assertThat(hash(id)).isEqualTo(digest(retryToken));
        assertThat(expires(id)).isEqualTo(expiry);
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND revision=2 AND status='SENT'", id)).isEqualTo(1);
    }

    @Test void processRestartAfterLostTokenRotatesRevisionAndRejectsLateOldWorkerCompletion() throws Exception {
        long id = create();
        Instant expiry = expires(id);
        var lost = claim("crashed-worker");
        byte[] lostHash = hash(id);
        clock.advance(settings.leaseDuration().plusSeconds(1));
        var restarted = new InvitationDeliveryService(repository, transactionManager, clock, settings);
        assertThat(restarted.claimNext("replacement-worker")).isEmpty();
        assertThat(revision(id)).isEqualTo(2);
        assertThat(hash(id)).isNull();
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND revision=1 AND status='SKIPPED'", id)).isEqualTo(1);
        clock.advance(settings.retryDelay().plusSeconds(1));
        var replacement = restarted.claimNext("replacement-worker").orElseThrow();
        assertThat(replacement.token()).isNotEqualTo(lost.token());
        assertThat(hash(id)).isNotEqualTo(lostHash).isEqualTo(digest(replacement.token()));
        delivery.succeeded(lost);
        delivery.failed(lost);
        assertThat(hash(id)).isEqualTo(digest(replacement.token()));
        assertThat(revision(id)).isEqualTo(2);
        restarted.succeeded(replacement);
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND revision=2 AND status='SENT'", id)).isEqualTo(1);
        assertThat(expires(id)).isEqualTo(expiry);
    }

    @Test void lostSuccessRecordAfterSmtpAcceptanceRecoversWithNewTokenInsteadOfResendingOldLink() throws Exception {
        long id = create();
        Instant expiry = expires(id);
        var completionFailure = workerWithFailingAudit("MAIL_SENT");
        assertThatThrownBy(completionFailure::runOnce).isInstanceOf(IllegalStateException.class).hasMessage("Controlled worker audit failure");
        assertThat(sender.received).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND revision=1 AND status='PROCESSING'", id)).isEqualTo(1);
        assertThat(hash(id)).isEqualTo(digest(sender.received.getFirst().token()));
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE branch_id=? AND action='MAIL_SENT'", branch)).isZero();
        String uncertainToken = sender.received.getFirst().token();
        sender.action = mail -> {};
        clock.advance(settings.leaseDuration().plusSeconds(1));
        assertThat(worker.runOnce()).isZero();
        assertThat(revision(id)).isEqualTo(2);
        assertThat(hash(id)).isNull();
        clock.advance(settings.retryDelay().plusSeconds(1));
        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(sender.received).hasSize(2);
        assertThat(sender.received.getLast().token()).isNotEqualTo(uncertainToken);
        assertThat(hash(id)).isEqualTo(digest(sender.received.getLast().token()));
        assertThat(expires(id)).isEqualTo(expiry);
        noRawTokenPersisted(id, uncertainToken);
    }

    @Test void acceptedCancelledAndExpiredInvitationsAreNeverSentAtClaimTime() {
        long accepted = create();
        jdbc.update("UPDATE employee_invitations SET status='ACCEPTED',accepted_employee_id=?,accepted_at=UTC_TIMESTAMP(6),token_hash=NULL,version=version+1 WHERE id=?", owner, accepted);
        long cancelled = create();
        management.cancel(owner, cancelled, "all", Map.of("version", version(cancelled)), "cancel-before-claim");
        long expired = create();
        jdbc.update("UPDATE employee_invitations SET expires_at=? WHERE id=?", Timestamp.from(clock.instant().minusSeconds(1)), expired);
        // Each invocation may consume one obsolete candidate while respecting batch limits.
        for (int i = 0; i < 4; i++) worker.runOnce();
        assertThat(sender.received).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs j JOIN employee_invitations i ON i.id=j.invitation_id WHERE i.branch_id=? AND j.status IN ('PENDING','PROCESSING','SENT')", branch)).isZero();
        assertThat(state(accepted)).isEqualTo("ACCEPTED");
        assertThat(state(cancelled)).isEqualTo("CANCELLED");
        assertThat(state(expired)).isEqualTo("EXPIRED");
    }

    @Test void finalPreflightStopsCancelledAcceptedAndLeaseExpiredClaims() {
        long cancelled = create();
        var cancelAttempt = claim("cancel-worker");
        management.cancel(owner, cancelled, "all", Map.of("version", version(cancelled)), "cancel-after-claim");
        assertThat(delivery.stillCurrent(cancelAttempt)).isFalse();
        delivery.succeeded(cancelAttempt);
        assertThat(state(cancelled)).isEqualTo("CANCELLED");
        long accepted = create();
        var acceptAttempt = claim("accept-worker");
        jdbc.update("UPDATE employee_invitations SET status='ACCEPTED',accepted_employee_id=?,accepted_at=UTC_TIMESTAMP(6),token_hash=NULL,version=version+1 WHERE id=?", owner, accepted);
        assertThat(delivery.stillCurrent(acceptAttempt)).isFalse();
        delivery.failed(acceptAttempt);
        assertThat(state(accepted)).isEqualTo("ACCEPTED");
        long leased = create();
        var leaseAttempt = claim("expired-lease-worker");
        clock.advance(settings.leaseDuration().plusSeconds(1));
        assertThat(delivery.stillCurrent(leaseAttempt)).isFalse();
        assertThat(sender.received).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id IN (?,?,?) AND status='SENT'", cancelled, accepted, leased)).isZero();
    }

    @Test void inFlightCancellationMayDeliverOldMailButNeverMarksCancelledInvitationSent() throws Exception {
        long id = create();
        sender.action = mail -> management.cancel(owner, id, "all", Map.of("version", version(id)), "inflight-cancel");
        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(sender.received).hasSize(1);
        assertThat(state(id)).isEqualTo("CANCELLED");
        assertThat(hash(id)).isNull();
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND status='SENT'", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND status='SKIPPED'", id)).isEqualTo(1);
        noRawTokenPersisted(id, sender.received.getFirst().token());
    }

    @Test void inFlightManualReissuePreventsOldCompletionFromOverwritingNewRevision() throws Exception {
        long id = create();
        Instant originalExpiry = expires(id);
        sender.action = mail -> management.reissue(owner, id, "all", Map.of("version", version(id)), "inflight-reissue");
        assertThat(worker.runOnce()).isEqualTo(1);
        String old = sender.received.getFirst().token();
        assertThat(revision(id)).isEqualTo(2);
        assertThat(hash(id)).isNull();
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND revision=1 AND status='SKIPPED'", id)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND revision=2 AND status='PENDING'", id)).isEqualTo(1);
        assertThat(expires(id)).isAfterOrEqualTo(originalExpiry);
        sender.action = mail -> {};
        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(sender.received.getLast().token()).isNotEqualTo(old);
        assertThat(hash(id)).isEqualTo(digest(sender.received.getLast().token()));
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND revision=2 AND status='SENT'", id)).isEqualTo(1);
    }

    @Test void twoWorkersShareOneClaimAndDoNotHoldDatabaseLocksDuringSlowSmtp() throws Exception {
        long id = create();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        sender.action = mail -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        };
        var competitor = new InvitationEmailWorker(new InvitationDeliveryService(repository, transactionManager, clock, settings), sender, settings);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(worker::runOnce);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            try { assertThat(pool.submit(competitor::runOnce).get(5, TimeUnit.SECONDS)).isZero(); }
            finally { release.countDown(); }
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(sender.received).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND status='SENT'", id)).isEqualTo(1);
    }

    @Test void retryBudgetStopsAutomaticDeliveryAndInvalidatesFinalToken() {
        long id = create();
        sender.action = mail -> { throw new IllegalStateException("controlled transport failure"); };
        for (int attempt = 0; attempt < settings.maxAttempts(); attempt++) {
            assertThat(worker.runOnce()).isEqualTo(1);
            clock.advance(settings.retryDelay().plusSeconds(1));
        }
        assertThat(sender.received).hasSize(settings.maxAttempts());
        assertThat(revision(id)).isEqualTo(settings.maxAttempts());
        assertThat(hash(id)).isNull();
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND status IN ('PENDING','PROCESSING')", id)).isZero();
        assertThat(worker.runOnce()).isZero();
        assertThat(state(id)).isEqualTo("PENDING");
    }

    @Test void workerRejectsAmbientTransactionBeforeAnyClaimOrMailAndRollbackPreservesPendingJob() {
        long id = create();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> worker.runOnce()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sender.received).isEmpty();
        assertThat(hash(id)).isNull();
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND revision=1 AND status='PENDING'", id)).isEqualTo(1);
        assertThat(worker.runOnce()).isEqualTo(1);
    }

    @Test void claimDatabaseFailureRollsBackHashLeaseAndDoesNotSendMail() {
        long id = create();
        // Fail after real audit/outbox SQL, without requiring elevated MySQL trigger privileges.
        var claimFailure = workerWithFailingAudit("MAIL_CLAIMED");
        assertThatThrownBy(claimFailure::runOnce).isInstanceOf(IllegalStateException.class).hasMessage("Controlled worker audit failure");
        assertThat(hash(id)).isNull();
        assertThat(count("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND status='PENDING' AND worker_id IS NULL AND locked_until IS NULL", id)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE branch_id=? AND action='MAIL_CLAIMED'", branch)).isZero();
        assertThat(sender.received).isEmpty();
    }

    private InvitationEmailWorker workerWithFailingAudit(String failedAction) {
        var faultyRepository = new InvitationDeliveryRepository(jdbc, json) {
            @Override public void changed(InvitationState invitation, Job job, String action) {
                super.changed(invitation, job, action);
                if (failedAction.equals(action)) throw new IllegalStateException("Controlled worker audit failure");
            }
        };
        return new InvitationEmailWorker(new InvitationDeliveryService(faultyRepository, transactionManager, clock, settings), sender, settings);
    }
}
