package com.openstudy.modules.invitation.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.modules.invitation.dto.Invitation;

@Repository
public class InvitationManagementRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public InvitationManagementRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Stored(long id, long branch, String email, String role, String state, long revision,
            long version, Instant expiresAt, Long acceptedEmployeeId, Invitation view) {}

    private record Row(long branch, String name, String email, String role, String state, long revision, long version,
            Instant createdAt, Instant updatedAt, Instant expiresAt, Long employee, String rejection) {}

    public Optional<Stored> find(long id, boolean lock, Instant now) {
        // Read the parent before its children. A child query inside the RowMapper borrows a second pooled
        // connection when no transaction is bound, and concurrent requests can then starve the pool.
        var found = jdbc.query("SELECT * FROM employee_invitations WHERE id=?" + (lock ? " FOR UPDATE" : ""), (rs, row) ->
                new Row(rs.getLong("branch_id"), rs.getString("invitee_name"), rs.getString("invitee_email"),
                        rs.getString("initial_role"), rs.getString("status"), rs.getLong("revision"), rs.getLong("version"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getObject("accepted_employee_id") == null ? null : rs.getLong("accepted_employee_id"),
                        rs.getString("rejection_reason")), id).stream().findFirst();
        if (found.isEmpty()) return Optional.empty();
        var row = found.get();
        var grants = jdbc.queryForList("SELECT d.code FROM invitation_permission_grants g "
                + "JOIN permission_definitions d ON d.id=g.permission_id WHERE g.invitation_id=? ORDER BY d.code"
                + (lock ? " FOR SHARE" : ""), String.class, id);
        var delivery = jdbc.queryForList("SELECT status FROM invitation_send_jobs WHERE invitation_id=? AND revision=?"
                + (lock ? " FOR SHARE" : ""), String.class, id, row.revision());
        var result = new Invitation(Long.toString(id), ApiVersion.fromDatabase(row.version()), row.createdAt(),
                row.updatedAt(), Long.toString(row.branch()), row.name(), row.email(),
                "MANAGER".equals(row.role()) ? "admin" : "staff", grants,
                status("PENDING".equals(row.state()) && !now.isBefore(row.expiresAt()) ? "EXPIRED" : row.state()),
                delivery(delivery.isEmpty() ? "SKIPPED" : delivery.getFirst()), row.expiresAt(),
                row.employee() == null ? null : row.employee().toString(), row.rejection());
        return Optional.of(new Stored(id, row.branch(), row.email(), row.role(), row.state(), row.revision(),
                row.version(), row.expiresAt(), row.employee(), result));
    }

    public List<Long> ids(Set<Long> branches) {
        if (branches.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(branches.size(), "?"));
        return jdbc.queryForList("SELECT id FROM employee_invitations WHERE branch_id IN (" + placeholders + ") ORDER BY id",
                Long.class, branches.toArray());
    }

    public void lock(long id) {
        jdbc.queryForList("SELECT id FROM employee_invitations WHERE id=? FOR UPDATE", Long.class, id);
    }

    public void lockCandidates(long branch, String email) {
        jdbc.queryForList("SELECT id FROM employee_invitations WHERE branch_id=? AND invitee_email=? "
                + "AND status IN ('PENDING','ACCEPTED') ORDER BY id FOR UPDATE", Long.class, branch, email);
    }

    public List<Stored> active(long branch, String email, Instant now) {
        return jdbc.queryForList("SELECT id FROM employee_invitations WHERE branch_id=? AND invitee_email=? "
                + "AND status IN ('PENDING','ACCEPTED') ORDER BY id FOR UPDATE", Long.class, branch, email)
                .stream().map(id -> find(id, true, now).orElseThrow()).toList();
    }

    public long create(long actor, long branch, String name, String email, String role, Long preset,
            List<String> permissions, Instant expires) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO employee_invitations "
                    + "(branch_id,invitee_name,invitee_email,initial_role,preset_id,created_by,updated_by,status,revision,expires_at) "
                    + "VALUES (?,?,?,?,?,?,?,'PENDING',1,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, branch);
            statement.setString(2, name);
            statement.setString(3, email);
            statement.setString(4, role);
            if (preset == null) statement.setNull(5, java.sql.Types.BIGINT); else statement.setLong(5, preset);
            statement.setLong(6, actor);
            statement.setLong(7, actor);
            statement.setTimestamp(8, Timestamp.from(expires));
            return statement;
        }, key);
        long id = Objects.requireNonNull(key.getKey()).longValue();
        for (String code : permissions) {
            jdbc.update("INSERT INTO invitation_permission_grants(invitation_id,permission_id) "
                    + "SELECT ?,id FROM permission_definitions WHERE code=?", id, code);
        }
        enqueue(id, 1);
        return id;
    }

    public void expire(long actor, Stored invitation) {
        jdbc.update("UPDATE employee_invitations SET status='EXPIRED',token_hash=NULL,updated_by=?,version=version+1 WHERE id=?",
                actor, invitation.id());
        skipJobs(invitation.id());
    }

    public void reissue(long actor, Stored invitation, Instant expires) {
        skipJobs(invitation.id());
        jdbc.update("UPDATE employee_invitations SET status='PENDING',token_hash=NULL,revision=revision+1,expires_at=?,"
                + "updated_by=?,version=version+1 WHERE id=?", Timestamp.from(expires), actor, invitation.id());
        enqueue(invitation.id(), invitation.revision() + 1);
    }

    public void cancel(long actor, long id) {
        jdbc.update("UPDATE employee_invitations SET status='CANCELLED',token_hash=NULL,cancelled_by=?,"
                + "cancelled_at=UTC_TIMESTAMP(6),updated_by=?,version=version+1 WHERE id=?", actor, actor, id);
        skipJobs(id);
    }

    public void reject(long actor, long id, String reason) {
        jdbc.update("UPDATE employee_invitations SET status='REJECTED',token_hash=NULL,rejected_by=?,"
                + "rejected_at=UTC_TIMESTAMP(6),rejection_reason=?,updated_by=?,version=version+1 WHERE id=?", actor, reason, actor, id);
        skipJobs(id);
    }

    /** Server-side view of a link holder's invitation. The token hash never leaves this layer. */
    public record Link(long id, long branch, String branchName, String name, String email, String role,
            String state, Instant expiresAt, byte[] tokenHash) {}

    public Optional<Link> link(byte[] tokenHash) {
        return jdbc.queryForList("SELECT id FROM employee_invitations WHERE token_hash=?", Long.class, tokenHash)
                .stream().findFirst().flatMap(id -> link(id, false));
    }

    public Optional<Link> link(long id, boolean lock) {
        return jdbc.query("SELECT i.id,i.branch_id,b.name branch_name,i.invitee_name,i.invitee_email,i.initial_role,"
                + "i.status,i.expires_at,i.token_hash FROM employee_invitations i JOIN branches b ON b.id=i.branch_id "
                + "WHERE i.id=?" + (lock ? " FOR UPDATE" : ""), (rs, row) -> new Link(rs.getLong("id"),
                        rs.getLong("branch_id"), rs.getString("branch_name"), rs.getString("invitee_name"),
                        rs.getString("invitee_email"), rs.getString("initial_role"), rs.getString("status"),
                        rs.getTimestamp("expires_at").toInstant(), rs.getBytes("token_hash")), id).stream().findFirst();
    }

    public void accept(long employee, long id) {
        jdbc.update("UPDATE employee_invitations SET status='ACCEPTED',token_hash=NULL,accepted_employee_id=?,"
                + "accepted_at=UTC_TIMESTAMP(6),updated_by=?,version=version+1 WHERE id=? AND status='PENDING'", employee, employee, id);
        skipJobs(id);
    }

    public void approve(long actor, long id) {
        jdbc.update("UPDATE employee_invitations SET status='APPROVED',approved_by=?,approved_at=UTC_TIMESTAMP(6),"
                + "updated_by=?,version=version+1 WHERE id=? AND status='ACCEPTED'", actor, actor, id);
    }

    private void enqueue(long invitation, long revision) {
        jdbc.update("INSERT INTO invitation_send_jobs(invitation_id,revision,status,next_attempt_at) VALUES (?,?,'PENDING',UTC_TIMESTAMP(6))",
                invitation, revision);
    }

    private void skipJobs(long invitation) {
        jdbc.update("UPDATE invitation_send_jobs SET status='SKIPPED',next_attempt_at=NULL,locked_until=NULL,worker_id=NULL "
                + "WHERE invitation_id=? AND status IN ('PENDING','PROCESSING','FAILED')", invitation);
    }

    public void changed(long actor, long branch, long id, String action, Invitation before, Invitation after, String requestId) {
        jdbc.update("INSERT INTO audit_events(actor_employee_id,branch_id,entity_type,entity_id,action,before_json,after_json,request_id) "
                + "VALUES (?,?,'invitations',?,?,?,?,?)", actor, branch, id, action,
                before == null ? null : json.writeValueAsString(before), json.writeValueAsString(after), requestId);
        jdbc.update("INSERT INTO workspace_event_outbox(branch_id,entity_type,entity_id,event_type) VALUES (?,'invitations',?,'INVALIDATE')",
                branch, id);
    }

    private record StoredRequest(long id, byte[] hash, String response, Instant expiresAt) {}

    public Optional<Invitation> replay(long actor, long branch, String key, Object input, Instant now) {
        var rows = jdbc.query("SELECT id,request_hash,response_json,expires_at FROM idempotency_records "
                + "WHERE actor_employee_id=? AND operation='createInvitation' AND scope_key=? AND idempotency_key=? FOR UPDATE",
                (rs, row) -> new StoredRequest(rs.getLong("id"), rs.getBytes("request_hash"),
                        rs.getString("response_json"), rs.getTimestamp("expires_at").toInstant()),
                actor, Long.toString(branch), key);
        if (rows.isEmpty()) return Optional.empty();
        var prior = rows.getFirst();
        if (!now.isBefore(prior.expiresAt())) {
            jdbc.update("DELETE FROM idempotency_records WHERE id=?", prior.id());
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(hash(input), prior.hash())) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키에 다른 입력을 사용할 수 없습니다.");
        }
        return Optional.of(json.readValue(prior.response(), Invitation.class));
    }

    public void remember(long actor, long branch, String key, Object input, Invitation response, Instant expires) {
        jdbc.update("INSERT INTO idempotency_records(actor_employee_id,operation,scope_key,idempotency_key,request_hash,"
                + "state,http_status,response_json,expires_at) VALUES (?,'createInvitation',?,?,?,'COMPLETED',201,?,?)",
                actor, Long.toString(branch), key, hash(input), json.writeValueAsString(response), Timestamp.from(expires));
    }

    private byte[] hash(Object input) {
        try { return MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(input).getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String status(String state) {
        return switch (state) {
            case "PENDING" -> "초대 대기";
            case "ACCEPTED" -> "승인 대기";
            case "APPROVED" -> "활성";
            case "CANCELLED" -> "취소";
            case "REJECTED" -> "거절";
            case "EXPIRED" -> "만료";
            default -> throw new IllegalStateException("Unknown invitation state");
        };
    }

    private static String delivery(String state) {
        return switch (state) {
            case "PENDING" -> "발송 대기";
            case "PROCESSING" -> "발송 중";
            case "SENT" -> "발송 완료";
            case "FAILED" -> "발송 실패";
            case "SKIPPED" -> "발송 생략";
            default -> throw new IllegalStateException("Unknown delivery state");
        };
    }
}
