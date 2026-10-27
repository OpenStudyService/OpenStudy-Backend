package com.openstudy.modules.employee.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Collections;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.openstudy.modules.employee.dto.EmployeeAccount;
import com.openstudy.modules.employee.dto.EmployeeMembership;
import com.openstudy.modules.employee.dto.EmployeeLookup;
import com.openstudy.modules.employee.dto.InvitationTarget;

@Repository
public class EmployeeAccountRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<EmployeeAccount> ACCOUNT = (rs, row) -> new EmployeeAccount(
            rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"), rs.getString("name"),
            rs.getString("nickname"), rs.getString("avatar_url"), rs.getString("global_role"),
            rs.getString("status"), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    public EmployeeAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EmployeeAccount> findByEmail(String email) {
        return jdbc.query("SELECT * FROM employees WHERE email = ?", ACCOUNT, email).stream().findFirst();
    }

    public Optional<EmployeeAccount> findById(long id) {
        return jdbc.query("SELECT * FROM employees WHERE id = ?", ACCOUNT, id).stream().findFirst();
    }

    public List<EmployeeLookup> lookup(Set<Long> branches) {
        if (branches.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(branches.size(), "?"));
        return jdbc.query("SELECT e.id,e.name FROM employees e WHERE e.status='ACTIVE' AND "
                + "(e.global_role='OWNER' OR EXISTS (SELECT 1 FROM branch_memberships m WHERE m.employee_id=e.id "
                + "AND m.status='ACTIVE' AND m.branch_id IN (" + placeholders + "))) ORDER BY e.id",
                (rs, row) -> new EmployeeLookup(rs.getString("id"), rs.getString("name")), branches.toArray());
    }

    public boolean relatedToBranches(long employee, Set<Long> branches) {
        if (branches.isEmpty()) return false;
        var args = new java.util.ArrayList<Object>(); args.add(employee); args.addAll(branches);
        String placeholders = String.join(",", Collections.nCopies(branches.size(), "?"));
        // Retain revoked memberships here: peers need a scope-free invalidation to remove stale choices.
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM employees e WHERE e.id=? "
                + "AND (e.global_role='OWNER' OR EXISTS(SELECT 1 FROM branch_memberships m WHERE m.employee_id=e.id "
                + "AND m.branch_id IN (" + placeholders + "))))", Boolean.class, args.toArray()));
    }

    public EmployeeAccount lockById(long id) {
        return jdbc.queryForObject("SELECT * FROM employees WHERE id = ? FOR UPDATE", ACCOUNT, id);
    }

    public Optional<InvitationTarget> invitationTarget(String email, long branch) {
        var accounts = jdbc.query("SELECT id,global_role,status FROM employees WHERE email=? FOR UPDATE",
                (rs, row) -> new InvitationTarget(rs.getLong("id"), "OWNER".equals(rs.getString("global_role")),
                        rs.getString("status"), null, null, null), email);
        if (accounts.isEmpty()) return Optional.empty();
        var account = accounts.getFirst();
        return Optional.of(jdbc.query("SELECT id,role,status FROM branch_memberships WHERE employee_id=? AND branch_id=? FOR UPDATE",
                (rs, row) -> new InvitationTarget(account.employeeId(), account.owner(), account.accountStatus(),
                        rs.getLong("id"), rs.getString("role"), rs.getString("status")), account.employeeId(), branch)
                .stream().findFirst().orElse(account));
    }

    public boolean revokePendingInvitationMembership(long employee, long branch, long actor) {
        int changed = jdbc.update("UPDATE branch_memberships SET status='REVOKED',updated_by=?,version=version+1 "
                + "WHERE employee_id=? AND branch_id=? AND status='PENDING'", actor, employee, branch);
        if (changed == 0) return false;
        jdbc.update("UPDATE employees SET updated_by=?,version=version+1 WHERE id=?", actor, employee);
        return true;
    }

    public long createInvitedAccount(String email, String passwordHash, String name) {
        jdbc.update("INSERT INTO employees (email,password_hash,name,nickname,global_role,status) "
                + "VALUES (?,?,?,?,'EMPLOYEE','PENDING')", email, passwordHash, name, name);
        return jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
    }

    /** Reuses a revoked row (Q11) or inserts one. A pending membership never carries effective grants. */
    public void pendInvitationMembership(long employee, long branch, String role, long actor) {
        var existing = jdbc.queryForList("SELECT id FROM branch_memberships WHERE employee_id=? AND branch_id=? FOR UPDATE",
                Long.class, employee, branch);
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO branch_memberships(employee_id,branch_id,role,status,created_by,updated_by) "
                    + "VALUES (?,?,?,'PENDING',?,?)", employee, branch, role, actor, actor);
        } else {
            int changed = jdbc.update("UPDATE branch_memberships SET role=?,status='PENDING',approved_by=NULL,approved_at=NULL,"
                    + "updated_by=?,version=version+1 WHERE id=? AND status='REVOKED'", role, actor, existing.getFirst());
            if (changed != 1) throw new IllegalStateException("Only a revoked membership can return to pending");
            jdbc.update("DELETE FROM membership_permission_grants WHERE membership_id=?", existing.getFirst());
        }
        jdbc.update("UPDATE employees SET updated_by=?,version=version+1 WHERE id=?", actor, employee);
    }

    /** Activates only the invitation's pending membership and a PENDING account; SUSPENDED/LEFT stay unchanged. */
    public void activateInvitationMembership(long employee, long branch, long actor, List<String> grants) {
        long membership = jdbc.queryForObject("SELECT id FROM branch_memberships WHERE employee_id=? AND branch_id=? "
                + "AND status='PENDING' FOR UPDATE", Long.class, employee, branch);
        jdbc.update("UPDATE branch_memberships SET status='ACTIVE',approved_by=?,approved_at=UTC_TIMESTAMP(6),"
                + "updated_by=?,version=version+1 WHERE id=?", actor, actor, membership);
        jdbc.update("DELETE FROM membership_permission_grants WHERE membership_id=?", membership);
        for (String code : grants) jdbc.update("INSERT INTO membership_permission_grants(membership_id,permission_id) "
                + "SELECT ?,id FROM permission_definitions WHERE code=?", membership, code);
        jdbc.update("UPDATE employees SET status=IF(status='PENDING','ACTIVE',status),updated_by=?,version=version+1 "
                + "WHERE id=? AND status IN ('PENDING','ACTIVE')", actor, employee);
    }

    public void updateNickname(long id, String nickname) {
        jdbc.update("UPDATE employees SET nickname=?,updated_by=?,version=version+1 WHERE id=?", nickname, id, id);
    }

    public List<EmployeeMembership> memberships(long employeeId) {
        return jdbc.query("SELECT id, branch_id, role FROM branch_memberships WHERE employee_id = ? "
                + "AND status = 'ACTIVE' ORDER BY branch_id", (rs, row) -> new EmployeeMembership(
                        rs.getString("branch_id"), "MANAGER".equals(rs.getString("role")) ? "admin" : "staff",
                        jdbc.queryForList("SELECT p.code FROM membership_permission_grants g "
                                + "JOIN permission_definitions p ON p.id = g.permission_id "
                                + "WHERE g.membership_id = ? ORDER BY p.code", String.class, rs.getLong("id"))), employeeId);
    }

    public void recordLogin(long id) {
        jdbc.update("UPDATE employees SET last_login_at = UTC_TIMESTAMP(6) WHERE id = ?", id);
    }

    public boolean ownerExists() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM employees WHERE global_role = 'OWNER')", Boolean.class));
    }

    public void createOwner(String email, String passwordHash) {
        jdbc.update("INSERT INTO employees (email,password_hash,name,nickname,global_role,status) "
                + "VALUES (?,?,'로컬 관리자','관리자','OWNER','ACTIVE')", email, passwordHash);
    }
}
