package com.openstudy.modules.authorization.repository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.security.access.AccessDeniedException;
import com.openstudy.modules.authorization.dto.AuthorizationState;

/** Own read model over the authorization aggregate, independent of employee/branch repositories. */
@Repository
public class AuthorizationRepository {
    private final JdbcTemplate jdbc;
    public AuthorizationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public AuthorizationState current(long employeeId) {
        return current(employeeId, false);
    }

    public AuthorizationState currentLocked(long employeeId) {
        return current(employeeId, true);
    }

    private AuthorizationState current(long employeeId, boolean locking) {
        String suffix = locking ? " FOR SHARE" : "";
        var account = jdbc.query("SELECT global_role,status FROM employees WHERE id=?" + suffix, (rs, n) ->
                new String[] {rs.getString(1), rs.getString(2)}, employeeId).stream().findFirst()
                .orElseThrow(() -> new AccessDeniedException("접근 권한이 없습니다."));
        Map<Long, String> branches = new HashMap<>();
        jdbc.query("SELECT id,status FROM branches ORDER BY id" + suffix, rs -> {
            branches.put(rs.getLong(1), rs.getString(2));
        });
        Map<Long, AuthorizationState.Membership> memberships = new HashMap<>();
        jdbc.query("SELECT id,branch_id,role,status FROM branch_memberships WHERE employee_id=? ORDER BY branch_id" + suffix,
                rs -> {
                    Set<String> grants = new HashSet<>(jdbc.queryForList("SELECT p.code FROM membership_permission_grants g "
                            + "JOIN permission_definitions p ON p.id=g.permission_id WHERE g.membership_id=?" + suffix,
                            String.class, rs.getLong(1)));
                    memberships.put(rs.getLong(2), new AuthorizationState.Membership(rs.getString(3), rs.getString(4), grants));
                }, employeeId);
        return new AuthorizationState(employeeId, "OWNER".equals(account[0]), account[1], memberships, branches);
    }

    public void lockGuard() { jdbc.queryForObject("SELECT id FROM administration_guard WHERE id=1 FOR UPDATE", Long.class); }
    public void lockEmployees() {
        // The singleton guard already serializes foundation writes. Lock administrator candidates too,
        // before branches, so last-administrator checks never acquire employee locks in reverse order.
        jdbc.queryForList("SELECT id FROM employees ORDER BY id FOR UPDATE", Long.class);
    }
    public void lockBranches() {
        // Foundation has few branches. Lock the branch set in canonical order, including read-model rows,
        // so a pre-existing REPEATABLE_READ snapshot cannot be used for an operating-status decision.
        jdbc.queryForList("SELECT id FROM branches ORDER BY id FOR UPDATE", Long.class);
    }
    public void lockMemberships(Set<Long> employeeIds, Set<Long> branchIds) {
        employeeIds.stream().sorted().forEach(employee -> branchIds.stream().sorted().forEach(branch ->
                jdbc.queryForList("SELECT id FROM branch_memberships WHERE employee_id=? AND branch_id=? FOR UPDATE",
                        Long.class, employee, branch)));
    }
    public long activeOwners() {
        return jdbc.queryForList("SELECT id FROM employees WHERE global_role='OWNER' AND status='ACTIVE' FOR SHARE", Long.class).size();
    }
    public long activeAdministrators(long branch) {
        return jdbc.queryForList("SELECT DISTINCT e.id FROM employees e LEFT JOIN branch_memberships m "
                + "ON m.employee_id=e.id AND m.branch_id=? AND m.status='ACTIVE' AND m.role='MANAGER' "
                + "WHERE e.status='ACTIVE' AND (e.global_role='OWNER' OR m.id IS NOT NULL) FOR SHARE", Long.class, branch).size();
    }
}
