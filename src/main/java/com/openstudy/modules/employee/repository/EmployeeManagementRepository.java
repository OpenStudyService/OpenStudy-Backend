package com.openstudy.modules.employee.repository;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.modules.employee.dto.ManagedEmployee;
import com.openstudy.modules.employee.dto.ManagedEmployee.Membership;

@Repository
public class EmployeeManagementRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public EmployeeManagementRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public List<Long> ids() { return jdbc.queryForList("SELECT id FROM employees ORDER BY id", Long.class); }
    public Optional<ManagedEmployee> find(long id, Set<Long> visible, boolean all, boolean lock) {
        return jdbc.query("SELECT id,version,created_at,updated_at,name,email,status,nickname,avatar_url,global_role FROM employees WHERE id=?"
                + (lock ? " FOR UPDATE" : ""), (rs, n) -> {
            var memberships = memberships(id, lock).stream().filter(m -> all || visible.contains(Long.parseLong(m.branchId()))).toList();
            return new ManagedEmployee(rs.getString("id"), ApiVersion.fromDatabase(rs.getLong("version")),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                    memberships.isEmpty() ? "all" : memberships.getFirst().branchId(), rs.getString("name"), rs.getString("email"),
                    status(rs.getString("status")), rs.getString("nickname"), Objects.toString(rs.getString("avatar_url"), ""),
                    "OWNER".equals(rs.getString("global_role")), memberships);
        }, id).stream().findFirst();
    }
    public List<Membership> memberships(long employee, boolean lock) {
        return jdbc.query("SELECT id,branch_id,role,status,version FROM branch_memberships WHERE employee_id=? ORDER BY branch_id"
                + (lock ? " FOR UPDATE" : ""), (rs, n) -> new Membership(rs.getString("branch_id"),
                        "MANAGER".equals(rs.getString("role")) ? "admin" : "staff", membershipStatus(rs.getString("status")),
                        jdbc.queryForList("SELECT p.code FROM membership_permission_grants g JOIN permission_definitions p ON p.id=g.permission_id WHERE g.membership_id=? ORDER BY p.code"
                                + (lock ? " FOR SHARE" : ""), String.class, rs.getLong("id")), ApiVersion.fromDatabase(rs.getLong("version"))), employee);
    }
    public void updateAccount(long id, long actor, String name, String status) {
        jdbc.update("UPDATE employees SET name=?,status=?,updated_by=?,version=version+1 WHERE id=?", name, status, actor, id);
        if (!"ACTIVE".equals(status)) {
            // The persisted Spring principal is the decimal employee ID. Delete every session now;
            // requests are independently guarded against current account status as well.
            jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", Long.toString(id));
        }
    }
    public void updateMembership(long employee, long branch, long actor, String role, String status, List<String> grants) {
        jdbc.update("UPDATE branch_memberships SET role=?,status=?,updated_by=?,version=version+1 WHERE employee_id=? AND branch_id=?", role, status, actor, employee, branch);
        long membership = jdbc.queryForObject("SELECT id FROM branch_memberships WHERE employee_id=? AND branch_id=?", Long.class, employee, branch);
        jdbc.update("DELETE FROM membership_permission_grants WHERE membership_id=?", membership);
        for (String code : grants) jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) SELECT ?,id FROM permission_definitions WHERE code=?", membership, code);
        jdbc.update("UPDATE employees SET updated_by=?,version=version+1 WHERE id=?", actor, employee);
    }
    public void changed(long actor, Long branch, long target, String action, Object before, Object after, String requestId) {
        changed(actor, branch, target, action, before, after, requestId, "AUTHORIZATION_CHANGED");
    }
    public void profileChanged(long actor, Object before, Object after, String requestId) {
        changed(actor, null, actor, "UPDATE_PROFILE", before, after, requestId, "INVALIDATE");
    }
    private void changed(long actor, Long branch, long target, String action, Object before, Object after, String requestId, String eventType) {
        jdbc.update("INSERT INTO audit_events(actor_employee_id,branch_id,entity_type,entity_id,action,before_json,after_json,request_id) VALUES (?,?,'employee',?,?,?,?,?)",
                actor, branch, target, action, json.writeValueAsString(before), json.writeValueAsString(after), requestId);
        jdbc.update("INSERT INTO workspace_event_outbox(branch_id,target_employee_id,entity_type,entity_id,event_type) VALUES (?,?,'employee',?,?)", branch, target, target, eventType);
        jdbc.update("INSERT INTO workspace_event_outbox(branch_id,entity_type,entity_id,event_type) VALUES (?,'employees',?,'INVALIDATE')", branch, target);
    }
    public static String status(String db) { return switch(db) { case "ACTIVE" -> "활성"; case "PENDING" -> "승인 대기"; case "SUSPENDED" -> "이용 제한"; case "LEFT" -> "비활성"; default -> throw new IllegalStateException(); }; }
    private static String membershipStatus(String db) { return switch(db) { case "ACTIVE" -> "활성"; case "PENDING" -> "승인 대기"; case "REVOKED" -> "회수"; default -> throw new IllegalStateException(); }; }
}
