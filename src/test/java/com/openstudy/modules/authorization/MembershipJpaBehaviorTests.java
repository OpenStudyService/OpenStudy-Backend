package com.openstudy.modules.authorization;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.repository.AuthorizationRepository;
import com.openstudy.modules.authorization.service.MembershipService;
import com.openstudy.modules.employee.dto.EmployeeMembership;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5-2: memberships moved to the authorization module (JPA-03). The authorization read model must equal the
 * removed JDBC code (the oracles below), and every membership command must advance the membership version exactly
 * once and the employee version exactly once, as the former UPDATE ... version=version+1 statements did.
 */
@MySqlIntegrationTest
@Transactional
class MembershipJpaBehaviorTests {

    @Autowired AuthorizationRepository authorization;
    @Autowired MembershipService memberships;
    @Autowired EmployeeAccountService employees;
    @Autowired JdbcTemplate jdbc;
    long actor;
    long employee;
    long active;
    long suspended;
    long other;

    @BeforeEach
    void seed() {
        actor = employee("ACTIVE");
        employee = employee("ACTIVE");
        active = branch("ACTIVE");
        suspended = branch("SUSPENDED");
        other = branch("ACTIVE");
        long manager = membership(employee, active, "MANAGER", "ACTIVE");
        grant(manager, "members:write"); grant(manager, "sales:read");
        long staff = membership(employee, suspended, "STAFF", "PENDING");
        grant(staff, "operations:write");
        membership(employee, other, "STAFF", "REVOKED");
    }

    @Test
    void authorizationStateAndAdministratorCountsEqualTheFormerSql() {
        assertThat(authorization.current(employee)).isEqualTo(formerState(employee));
        assertThat(authorization.currentLocked(employee)).isEqualTo(formerState(employee));
        assertThat(authorization.activeAdministrators(active)).isEqualTo(formerAdministrators(active));
        assertThat(authorization.activeAdministrators(other)).isEqualTo(formerAdministrators(other));
        assertThat(authorization.activeOwners()).isEqualTo(
                jdbc.queryForList("SELECT id FROM employees WHERE global_role='OWNER' AND status='ACTIVE'", Long.class).size());
        assertThatThrownBy(() -> authorization.current(Long.MAX_VALUE))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void currentUserMembershipsEqualTheFormerSql() {
        assertThat(employees.memberships(employee)).isEqualTo(formerMemberships(employee));
        assertThat(employees.memberships(employee)).extracting(EmployeeMembership::branchId).containsExactly(Long.toString(active));
    }

    @Test
    void invitationLifecycleAdvancesBothVersionsOncePerStep() {
        long invitee = employee("PENDING");
        long branch = branch("ACTIVE");

        employees.pendInvitationMembership(invitee, branch, "STAFF", "pend");
        assertThat(row(invitee, branch)).containsEntry("status", "PENDING").containsEntry("version", 0L);
        assertThat(employeeVersion(invitee)).isEqualTo(1);

        employees.activateInvitationMembership(invitee, branch, actor, List.of("operations:write", "unknown:code"), "approve");
        var approved = row(invitee, branch);
        assertThat(approved).containsEntry("status", "ACTIVE").containsEntry("version", 1L).containsEntry("approved_by", actor);
        assertThat(((Number) approved.get("approved_age")).longValue()).isBetween(0L, 60L);
        assertThat(codes(invitee, branch)).containsExactly("operations:write");
        assertThat(jdbc.queryForObject("SELECT status FROM employees WHERE id=?", String.class, invitee)).isEqualTo("ACTIVE");
        assertThat(employeeVersion(invitee)).isEqualTo(2);

        // A revoked membership returns to pending (Q11) without its approval or grants. Every membership write goes
        // through MembershipService, so the persistence context never holds a row changed behind its back.
        memberships.update(invitee, branch, actor, "STAFF", "REVOKED", List.of("operations:write"));
        assertThat(row(invitee, branch)).containsEntry("status", "REVOKED").containsEntry("version", 2L);
        employees.pendInvitationMembership(invitee, branch, "MANAGER", "again");
        assertThat(row(invitee, branch)).containsEntry("status", "PENDING").containsEntry("role", "MANAGER")
                .containsEntry("version", 3L).containsEntry("approved_by", null);
        assertThat(codes(invitee, branch)).isEmpty();
        assertThat(employeeVersion(invitee)).isEqualTo(3);

        employees.revokePendingInvitationMembership(invitee, branch, actor, "reject");
        assertThat(row(invitee, branch)).containsEntry("status", "REVOKED").containsEntry("version", 4L);
        assertThat(employeeVersion(invitee)).isEqualTo(4);
        // Nothing pending any more: no change, no version bump.
        employees.revokePendingInvitationMembership(invitee, branch, actor, "reject-again");
        assertThat(row(invitee, branch)).containsEntry("version", 4L);
        assertThat(employeeVersion(invitee)).isEqualTo(4);

        assertThatThrownBy(() -> memberships.pend(employee, active, "STAFF", employee)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updateAdvancesOnceWhetherNothingOnlyGrantsOrEverythingChanges() {
        memberships.update(employee, active, actor, "MANAGER", "ACTIVE", List.of("members:write", "sales:read"));
        assertThat(row(employee, active)).containsEntry("version", 1L);
        memberships.update(employee, active, actor, "MANAGER", "ACTIVE", List.of("sales:read", "payments:read"));
        assertThat(row(employee, active)).containsEntry("version", 2L);
        assertThat(codes(employee, active)).containsExactly("payments:read", "sales:read");
        memberships.update(employee, active, actor, "STAFF", "REVOKED", List.of());
        assertThat(row(employee, active)).containsEntry("version", 3L).containsEntry("role", "STAFF").containsEntry("status", "REVOKED");
        assertThat(codes(employee, active)).isEmpty();
        assertThat(memberships.all(employee, true)).extracting(m -> m.dbVersion()).containsExactly(3L, 0L, 0L);
    }

    private AuthorizationState formerState(long employeeId) {
        var account = jdbc.queryForList("SELECT global_role,status FROM employees WHERE id=?", employeeId).getFirst();
        Map<Long, String> branches = new HashMap<>();
        jdbc.query("SELECT id,status FROM branches ORDER BY id", rs -> { branches.put(rs.getLong(1), rs.getString(2)); });
        Map<Long, AuthorizationState.Membership> rows = new HashMap<>();
        jdbc.query("SELECT id,branch_id,role,status FROM branch_memberships WHERE employee_id=? ORDER BY branch_id", rs -> {
            Set<String> grants = new HashSet<>(jdbc.queryForList("SELECT p.code FROM membership_permission_grants g "
                    + "JOIN permission_definitions p ON p.id=g.permission_id WHERE g.membership_id=?", String.class, rs.getLong(1)));
            rows.put(rs.getLong(2), new AuthorizationState.Membership(rs.getString(3), rs.getString(4), grants));
        }, employeeId);
        return new AuthorizationState(employeeId, "OWNER".equals(account.get("global_role")), (String) account.get("status"), rows, branches);
    }

    private long formerAdministrators(long branch) {
        return jdbc.queryForList("SELECT DISTINCT e.id FROM employees e LEFT JOIN branch_memberships m "
                + "ON m.employee_id=e.id AND m.branch_id=? AND m.status='ACTIVE' AND m.role='MANAGER' "
                + "WHERE e.status='ACTIVE' AND (e.global_role='OWNER' OR m.id IS NOT NULL)", Long.class, branch).size();
    }

    private List<EmployeeMembership> formerMemberships(long employeeId) {
        return jdbc.query("SELECT id, branch_id, role FROM branch_memberships WHERE employee_id = ? "
                + "AND status = 'ACTIVE' ORDER BY branch_id", (rs, row) -> new EmployeeMembership(
                        rs.getString("branch_id"), "MANAGER".equals(rs.getString("role")) ? "admin" : "staff",
                        jdbc.queryForList("SELECT p.code FROM membership_permission_grants g "
                                + "JOIN permission_definitions p ON p.id = g.permission_id "
                                + "WHERE g.membership_id = ? ORDER BY p.code", String.class, rs.getLong("id"))), employeeId);
    }

    private Map<String, Object> row(long employeeId, long branchId) {
        var row = new HashMap<>(jdbc.queryForMap("SELECT role,status,version,approved_by,"
                + "TIMESTAMPDIFF(SECOND,approved_at,UTC_TIMESTAMP(6)) approved_age FROM branch_memberships "
                + "WHERE employee_id=? AND branch_id=?", employeeId, branchId));
        row.computeIfPresent("version", (k, v) -> ((Number) v).longValue());
        row.computeIfPresent("approved_by", (k, v) -> ((Number) v).longValue());
        return row;
    }

    private List<String> codes(long employeeId, long branchId) {
        return jdbc.queryForList("SELECT p.code FROM membership_permission_grants g JOIN permission_definitions p ON p.id=g.permission_id "
                + "JOIN branch_memberships m ON m.id=g.membership_id WHERE m.employee_id=? AND m.branch_id=? ORDER BY p.code",
                String.class, employeeId, branchId);
    }

    private long employeeVersion(long id) { return jdbc.queryForObject("SELECT version FROM employees WHERE id=?", Long.class, id); }

    private long employee(String status) {
        String email = "membership-" + UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO employees (email,password_hash,name,nickname,global_role,status) VALUES (?,'hash','소속','소속','EMPLOYEE',?)",
                email, status);
        return jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
    }

    private long branch(String status) {
        String code = "MB-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO branches (code,name,address,status) VALUES (?,'소속 지점','주소',?)", code, status);
        return jdbc.queryForObject("SELECT id FROM branches WHERE code=?", Long.class, code);
    }

    private long membership(long employeeId, long branchId, String role, String status) {
        jdbc.update("INSERT INTO branch_memberships (employee_id,branch_id,role,status) VALUES (?,?,?,?)", employeeId, branchId, role, status);
        return jdbc.queryForObject("SELECT id FROM branch_memberships WHERE employee_id=? AND branch_id=?", Long.class, employeeId, branchId);
    }

    private void grant(long membership, String code) {
        jdbc.update("INSERT INTO membership_permission_grants (membership_id,permission_id) SELECT ?,id FROM permission_definitions WHERE code=?",
                membership, code);
    }
}
