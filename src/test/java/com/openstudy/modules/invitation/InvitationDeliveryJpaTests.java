package com.openstudy.modules.invitation;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.modules.invitation.repository.InvitationDeliveryRepository;
import com.openstudy.modules.invitation.repository.InvitationDeliveryRepository.Candidate;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6-1: the mail worker's persistence moved to JPA. Reads must return what the former SQL returned (the
 * oracles below are the removed statements), and each transition must leave the rows the former UPDATE/INSERT left.
 */
@MySqlIntegrationTest
@Transactional
class InvitationDeliveryJpaTests {
    static final byte[] HASH = new byte[32];

    @Autowired InvitationDeliveryRepository delivery;
    @Autowired JdbcTemplate jdbc;
    long creator;
    long branch;
    Instant now;

    @BeforeEach
    void seed() {
        String email = "delivery-" + UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO employees (email,password_hash,name,nickname,global_role,status) VALUES (?,'hash','발송','발송','EMPLOYEE','ACTIVE')", email);
        creator = jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
        String code = "DL-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO branches (code,name,address) VALUES (?,'발송 지점','주소')", code);
        branch = jdbc.queryForObject("SELECT id FROM branches WHERE code=?", Long.class, code);
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        HASH[0] = 7;
    }

    @Test
    void candidatesAndLockedSnapshotsEqualTheFormerSql() {
        long due = invitation(); job(due, 0, "PENDING", 0, now.minusSeconds(5), null, null);
        long later = invitation(); job(later, 0, "PENDING", 0, now.plusSeconds(600), null, null);
        long expired = invitation(); job(expired, 0, "PROCESSING", 1, null, now.minusSeconds(1), "w1");
        long live = invitation(); job(live, 0, "PROCESSING", 1, null, now.plusSeconds(600), "w2");
        long done = invitation(); job(done, 0, "SENT", 1, null, null, null);

        var former = jdbc.query("SELECT invitation_id,id FROM invitation_send_jobs WHERE "
                + "(status='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at<=?)) OR "
                + "(status='PROCESSING' AND (locked_until IS NULL OR locked_until<=?)) ORDER BY id LIMIT ?",
                (rs, row) -> new Candidate(rs.getLong("invitation_id"), rs.getLong("id")), Timestamp.from(now), Timestamp.from(now), 1000);
        assertThat(delivery.candidates(now, 1000)).isEqualTo(former);
        assertThat(delivery.candidates(now, 1000)).extracting(Candidate::invitationId).contains(due, expired).doesNotContain(later, live, done);
        assertThat(delivery.candidates(now, 1)).hasSize(1);

        var state = delivery.invitationForUpdate(expired).orElseThrow();
        assertThat(state.revision()).isZero();
        assertThat(state.status()).isEqualTo("PENDING");
        assertThat(state.hasToken()).isFalse();
        var job = delivery.jobForUpdate(jobOf(expired)).orElseThrow();
        assertThat(job).isEqualTo(new InvitationDeliveryRepository.Job(jobOf(expired), expired, 0, "PROCESSING", 1, null,
                now.minusSeconds(1), "w1"));
    }

    @Test
    void claimThenSentLeaveTheFormerRows() {
        long id = invitation(); long jobId = job(id, 0, "PENDING", 0, null, null, null);
        var state = delivery.invitationForUpdate(id).orElseThrow();
        var job = delivery.jobForUpdate(jobId).orElseThrow();
        Instant lease = now.plusSeconds(60);

        delivery.claim(state, job, HASH, "worker-1", lease);
        assertThat(invitationRow(id)).containsEntry("version", 1L).containsEntry("updated_by", null).containsEntry("revision", 0L);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM employee_invitations WHERE id=?", byte[].class, id)).isEqualTo(HASH);
        assertThat(jobRow(jobId)).containsEntry("status", "PROCESSING").containsEntry("attempts", 1L).containsEntry("worker_id", "worker-1")
                .containsEntry("next_attempt_at", null).containsEntry("last_error_code", null);
        assertThat(delivery.jobForUpdate(jobId).orElseThrow().leaseUntil()).isEqualTo(lease);

        delivery.sent(delivery.invitationForUpdate(id).orElseThrow(), delivery.jobForUpdate(jobId).orElseThrow(), now);
        assertThat(jobRow(jobId)).containsEntry("status", "SENT").containsEntry("worker_id", null).containsEntry("locked_until", null);
        assertThat(invitationRow(id)).containsEntry("version", 2L);
    }

    @Test
    void retryExhaustSkipAndExpireLeaveTheFormerRows() {
        long id = invitation(); long jobId = job(id, 0, "PROCESSING", 2, null, now.plusSeconds(60), "w");
        Instant due = now.plusSeconds(300);
        delivery.reserveRetry(delivery.invitationForUpdate(id).orElseThrow(), delivery.jobForUpdate(jobId).orElseThrow(), due, "SMTP_FAILED", true);
        assertThat(jobRow(jobId)).containsEntry("status", "FAILED").containsEntry("last_error_code", "SMTP_FAILED").containsEntry("worker_id", null);
        assertThat(invitationRow(id)).containsEntry("revision", 1L).containsEntry("version", 1L);
        long next = jdbc.queryForObject("SELECT id FROM invitation_send_jobs WHERE invitation_id=? AND revision=1", Long.class, id);
        assertThat(jobRow(next)).containsEntry("status", "PENDING").containsEntry("attempts", 2L);
        assertThat(delivery.jobForUpdate(next).orElseThrow().nextAttemptAt()).isEqualTo(due);

        delivery.exhaust(delivery.invitationForUpdate(id).orElseThrow(), delivery.jobForUpdate(next).orElseThrow(), "ATTEMPTS_EXHAUSTED");
        assertThat(jobRow(next)).containsEntry("status", "FAILED").containsEntry("last_error_code", "ATTEMPTS_EXHAUSTED");
        assertThat(invitationRow(id)).containsEntry("version", 2L).containsEntry("revision", 1L);

        long other = invitation(); long otherJob = job(other, 0, "PENDING", 0, null, null, null);
        delivery.skip(delivery.jobForUpdate(otherJob).orElseThrow(), "SUPERSEDED_REVISION");
        assertThat(jobRow(otherJob)).containsEntry("status", "SKIPPED").containsEntry("last_error_code", "SUPERSEDED_REVISION");
        assertThat(invitationRow(other)).containsEntry("version", 0L);

        long expiring = invitation();
        long open = job(expiring, 0, "PENDING", 0, null, null, null);
        long sent = job(expiring, 1, "SENT", 1, null, null, null);
        delivery.expire(delivery.invitationForUpdate(expiring).orElseThrow());
        assertThat(invitationRow(expiring)).containsEntry("status", "EXPIRED").containsEntry("version", 1L);
        assertThat(jobRow(open)).containsEntry("status", "SKIPPED").containsEntry("last_error_code", "INVITATION_EXPIRED");
        assertThat(jobRow(sent)).containsEntry("status", "SENT").containsEntry("last_error_code", null);
    }

    private long invitation() {
        String email = "invitee-" + UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO employee_invitations (branch_id,invitee_email,invitee_name,initial_role,created_by,status,expires_at) "
                + "VALUES (?,?,'초대','STAFF',?,'PENDING',?)", branch, email, creator, Timestamp.from(now.plusSeconds(86400)));
        return jdbc.queryForObject("SELECT id FROM employee_invitations WHERE invitee_email=?", Long.class, email);
    }

    private long job(long invitation, int revision, String status, int attempts, Instant next, Instant lease, String worker) {
        jdbc.update("INSERT INTO invitation_send_jobs (invitation_id,revision,status,attempts,next_attempt_at,locked_until,worker_id) "
                + "VALUES (?,?,?,?,?,?,?)", invitation, revision, status, attempts, next == null ? null : Timestamp.from(next),
                lease == null ? null : Timestamp.from(lease), worker);
        return jdbc.queryForObject("SELECT id FROM invitation_send_jobs WHERE invitation_id=? AND revision=?", Long.class, invitation, revision);
    }

    private long jobOf(long invitation) {
        return jdbc.queryForObject("SELECT id FROM invitation_send_jobs WHERE invitation_id=? AND revision=0", Long.class, invitation);
    }

    private Map<String, Object> invitationRow(long id) {
        return longs(jdbc.queryForMap("SELECT status,revision,version,updated_by,token_hash IS NULL no_token FROM employee_invitations WHERE id=?", id));
    }

    private Map<String, Object> jobRow(long id) {
        return longs(jdbc.queryForMap("SELECT status,attempts,worker_id,locked_until,next_attempt_at,last_error_code FROM invitation_send_jobs WHERE id=?", id));
    }

    private static Map<String, Object> longs(Map<String, Object> row) {
        var copy = new HashMap<String, Object>();
        row.forEach((k, v) -> copy.put(k, v instanceof Number n && !(v instanceof Double) ? n.longValue() : v));
        return copy;
    }
}
