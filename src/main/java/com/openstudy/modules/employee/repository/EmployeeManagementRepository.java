package com.openstudy.modules.employee.repository;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.outbox.WorkspaceEventType;
import com.openstudy.common.outbox.WorkspaceOutbox;

@Repository
public class EmployeeManagementRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditTrail audit;
    private final WorkspaceOutbox outbox;
    public EmployeeManagementRepository(JdbcTemplate jdbc, ObjectMapper json, AuditTrail audit, WorkspaceOutbox outbox) {
        this.jdbc = jdbc; this.json = json; this.audit = audit; this.outbox = outbox;
    }

    public List<Long> ids() { return jdbc.queryForList("SELECT id FROM employees ORDER BY id", Long.class); }
    /** Account columns of a managed employee; memberships are attached by the service (JPA-03). */
    public record Account(String id, long dbVersion, java.time.Instant createdAt, java.time.Instant updatedAt, String name,
            String email, String status, String nickname, String avatarUrl, boolean owner) {}
    public Optional<Account> account(long id, boolean lock) {
        return jdbc.query("SELECT id,version,created_at,updated_at,name,email,status,nickname,avatar_url,global_role FROM employees WHERE id=?"
                + (lock ? " FOR UPDATE" : ""), (rs, n) -> new Account(rs.getString("id"), rs.getLong("version"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), rs.getString("name"),
                        rs.getString("email"), rs.getString("status"), rs.getString("nickname"),
                        Objects.toString(rs.getString("avatar_url"), ""), "OWNER".equals(rs.getString("global_role"))), id).stream().findFirst();
    }
    public void updateAccount(long id, long actor, String name, String status) {
        jdbc.update("UPDATE employees SET name=?,status=?,updated_by=?,version=version+1 WHERE id=?", name, status, actor, id);
        if (!"ACTIVE".equals(status)) {
            // The persisted Spring principal is the decimal employee ID. Delete every session now;
            // requests are independently guarded against current account status as well.
            jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", Long.toString(id));
        }
    }
    /** A membership of this employee changed: the account version advances with it, as before. */
    public void touch(long employee, long actor) {
        jdbc.update("UPDATE employees SET updated_by=?,version=version+1 WHERE id=?", actor, employee);
    }
    public void changed(long actor, Long branch, long target, String action, Object before, Object after, String requestId) {
        changed(actor, branch, target, action, before, after, requestId, WorkspaceEventType.AUTHORIZATION_CHANGED);
    }
    public void profileChanged(long actor, Object before, Object after, String requestId) {
        changed(actor, null, actor, "UPDATE_PROFILE", before, after, requestId, WorkspaceEventType.INVALIDATE);
    }
    private void changed(long actor, Long branch, long target, String action, Object before, Object after, String requestId, WorkspaceEventType eventType) {
        audit.record(AuditEvent.change(actor, branch, "employee", target, action,
                json.writeValueAsString(before), json.writeValueAsString(after), requestId));
        outbox.notifyEmployee(branch, target, "employee", target, eventType);
        outbox.invalidate(branch, "employees", target);
    }
    public static String status(String db) { return switch(db) { case "ACTIVE" -> "활성"; case "PENDING" -> "승인 대기"; case "SUSPENDED" -> "이용 제한"; case "LEFT" -> "비활성"; default -> throw new IllegalStateException(); }; }
    public static String membershipStatus(String db) { return switch(db) { case "ACTIVE" -> "활성"; case "PENDING" -> "승인 대기"; case "REVOKED" -> "회수"; default -> throw new IllegalStateException(); }; }
}
