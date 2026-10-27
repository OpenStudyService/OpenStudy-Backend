package com.openstudy.modules.invitation.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.outbox.WorkspaceOutbox;

/** Worker persistence; guarded transitions lock the invitation parent before its job. */
@Repository
public class InvitationDeliveryRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    private final AuditTrail audit;
    private final WorkspaceOutbox outbox;

    public InvitationDeliveryRepository(JdbcTemplate jdbc, ObjectMapper json, AuditTrail audit, WorkspaceOutbox outbox) {
        this.jdbc = jdbc; this.json = json; this.audit = audit; this.outbox = outbox;
    }

    public record Candidate(long invitationId, long jobId) {}
    public record InvitationState(long id, long branchId, String status, long revision, Instant expiresAt,
            String email, String name, byte[] tokenHash) {
        public boolean hasToken() { return tokenHash != null; }
        @Override public String toString() { return "InvitationState[id=" + id + ",revision=" + revision + "]"; }
    }
    public record Job(long id, long invitationId, long revision, String status, int attempts,
            Instant nextAttemptAt, Instant leaseUntil, String workerId) {}

    public void lockGuard() {
        jdbc.queryForObject("SELECT id FROM administration_guard WHERE id=1 FOR UPDATE", Long.class);
    }

    /** Non-locking bounded scan: do not acquire a job lock before its parent invitation. */
    public List<Candidate> candidates(Instant now, int limit) {
        return jdbc.query("SELECT invitation_id,id FROM invitation_send_jobs WHERE "
                + "(status='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at<=?)) OR "
                + "(status='PROCESSING' AND (locked_until IS NULL OR locked_until<=?)) ORDER BY id LIMIT ?",
                (rs, row) -> new Candidate(rs.getLong("invitation_id"), rs.getLong("id")),
                Timestamp.from(now), Timestamp.from(now), limit);
    }

    public Optional<InvitationState> invitationForUpdate(long id) {
        return jdbc.query("SELECT id,branch_id,status,revision,expires_at,invitee_email,invitee_name,token_hash "
                + "FROM employee_invitations WHERE id=? FOR UPDATE", (rs, row) ->
                new InvitationState(rs.getLong("id"), rs.getLong("branch_id"), rs.getString("status"), rs.getLong("revision"),
                        rs.getTimestamp("expires_at").toInstant(), rs.getString("invitee_email"), rs.getString("invitee_name"),
                        rs.getBytes("token_hash")), id).stream().findFirst();
    }

    public Optional<Job> jobForUpdate(long id) {
        return jdbc.query("SELECT id,invitation_id,revision,status,attempts,next_attempt_at,locked_until,worker_id "
                + "FROM invitation_send_jobs WHERE id=? FOR UPDATE", (rs, row) ->
                new Job(rs.getLong("id"), rs.getLong("invitation_id"), rs.getLong("revision"), rs.getString("status"),
                        rs.getInt("attempts"), instant(rs.getTimestamp("next_attempt_at")),
                        instant(rs.getTimestamp("locked_until")), rs.getString("worker_id")), id).stream().findFirst();
    }

    public void claim(InvitationState invitation, Job job, byte[] hash, String workerId, Instant leaseUntil) {
        jdbc.update("UPDATE employee_invitations SET token_hash=?,updated_by=NULL,version=version+1 WHERE id=?", hash, invitation.id());
        jdbc.update("UPDATE invitation_send_jobs SET status='PROCESSING',attempts=attempts+1,worker_id=?,locked_until=?,"
                + "next_attempt_at=NULL,last_error_code=NULL WHERE id=?", workerId, Timestamp.from(leaseUntil), job.id());
    }

    public void sent(InvitationState invitation, Job job, Instant now) {
        jdbc.update("UPDATE invitation_send_jobs SET status='SENT',sent_at=?,locked_until=NULL,worker_id=NULL,"
                + "next_attempt_at=NULL,last_error_code=NULL WHERE id=?", Timestamp.from(now), job.id());
        jdbc.update("UPDATE employee_invitations SET updated_by=NULL,version=version+1 WHERE id=?", invitation.id());
    }

    public void reserveRetry(InvitationState invitation, Job job, Instant due, String reason, boolean failed) {
        finishOld(job, failed ? "FAILED" : "SKIPPED", reason);
        jdbc.update("UPDATE employee_invitations SET token_hash=NULL,revision=revision+1,updated_by=NULL,version=version+1 WHERE id=?",
                invitation.id());
        // Attempts is cumulative within this automatic chain. Manual reissue creates attempts=0.
        jdbc.update("INSERT INTO invitation_send_jobs(invitation_id,revision,status,attempts,next_attempt_at) VALUES (?,?,'PENDING',?,?)",
                invitation.id(), invitation.revision() + 1, job.attempts(), Timestamp.from(due));
    }

    public void exhaust(InvitationState invitation, Job job, String reason) {
        finishOld(job, "FAILED", reason);
        jdbc.update("UPDATE employee_invitations SET token_hash=NULL,updated_by=NULL,version=version+1 WHERE id=?", invitation.id());
    }

    public void skip(Job job, String reason) { finishOld(job, "SKIPPED", reason); }

    private void finishOld(Job job, String state, String reason) {
        jdbc.update("UPDATE invitation_send_jobs SET status=?,next_attempt_at=NULL,locked_until=NULL,worker_id=NULL,"
                + "last_error_code=? WHERE id=?", state, reason, job.id());
    }

    public void expire(InvitationState invitation) {
        jdbc.update("UPDATE employee_invitations SET status='EXPIRED',token_hash=NULL,updated_by=NULL,version=version+1 WHERE id=?",
                invitation.id());
        jdbc.update("UPDATE invitation_send_jobs SET status='SKIPPED',next_attempt_at=NULL,locked_until=NULL,worker_id=NULL,"
                + "last_error_code='INVITATION_EXPIRED' WHERE invitation_id=? AND status IN ('PENDING','PROCESSING','FAILED')",
                invitation.id());
    }

    /** Operational evidence has IDs and fixed state codes only; no recipient, token or token hash. */
    public void changed(InvitationState invitation, Job job, String action) {
        String snapshot = json.writeValueAsString(Map.of("jobId", Long.toString(job.id()), "revision", job.revision()));
        audit.record(AuditEvent.change(null, invitation.branchId(), "invitations", invitation.id(), action, null, snapshot, null));
        outbox.invalidate(invitation.branchId(), "invitations", invitation.id());
    }

    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
}
