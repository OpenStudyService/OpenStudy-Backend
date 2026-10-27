package com.openstudy.modules.employee;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.modules.employee.dto.EmployeeAccount;
import com.openstudy.modules.employee.dto.EmployeeLookup;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.employee.service.EmployeeManagementService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5-3: employee account behaviors of the former SQL that the HTTP tests do not pin down. The oracles below
 * are the removed queries and row mapping, run against the same rows.
 */
@MySqlIntegrationTest
@Transactional
class EmployeeJpaBehaviorTests {

    @Autowired EmployeeAccountService accounts;
    @Autowired EmployeeManagementService management;
    @Autowired JdbcTemplate jdbc;
    long owner;
    long employee;
    long branch;

    @BeforeEach
    void seed() {
        // The single-owner slot may already be taken by fixtures of other suites; reuse that owner if so.
        var existing = jdbc.queryForList("SELECT id FROM employees WHERE global_role='OWNER' AND status='ACTIVE'", Long.class);
        owner = existing.isEmpty() ? employee("OWNER", "ACTIVE") : existing.getFirst();
        employee = employee("EMPLOYEE", "ACTIVE");
        String code = "EM-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO branches (code,name,address) VALUES (?,'직원 지점','주소')", code);
        branch = jdbc.queryForObject("SELECT id FROM branches WHERE code=?", Long.class, code);
        jdbc.update("INSERT INTO branch_memberships (employee_id,branch_id,role,status) VALUES (?,?,'STAFF','ACTIVE')", employee, branch);
    }

    @Test
    void accountReadsEqualTheFormerSqlAndEmailIgnoresCase() {
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, employee);
        assertThat(accounts.byId(employee)).isEqualTo(former(employee));
        assertThat(accounts.byEmail(email.toUpperCase())).isEqualTo(former(employee));
        assertThat(accounts.exists(" " + email + " ")).isTrue();
    }

    @Test
    void lookupAndRelatedToBranchesEqualTheFormerSql() {
        assertThat(accounts.lookup(Set.of(branch))).isEqualTo(formerLookup(branch));
        assertThat(accounts.lookup(Set.of())).isEmpty();
        assertThat(accounts.relatedToBranches(employee, Set.of(branch))).isTrue();
        assertThat(accounts.relatedToBranches(employee, Set.of(Long.MAX_VALUE))).isFalse();
        assertThat(accounts.relatedToBranches(owner, Set.of(Long.MAX_VALUE))).isTrue();
    }

    @Test
    void loginTimeDoesNotAdvanceTheVersionButEveryProfileSaveDoes() {
        long version = version(employee);
        accounts.recordLogin(employee);
        assertThat(version(employee)).isEqualTo(version);
        assertThat(jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND,last_login_at,UTC_TIMESTAMP(6)) FROM employees WHERE id=?",
                Long.class, employee)).isBetween(0L, 60L);

        String nickname = accounts.byId(employee).nickname();
        accounts.updateProfile(employee, Map.of("nickname", nickname, "version", version + 1), "same-nickname");
        assertThat(version(employee)).isEqualTo(version + 1);
        assertThat(jdbc.queryForObject("SELECT updated_by FROM employees WHERE id=?", Long.class, employee)).isEqualTo(employee);
    }

    @Test
    void approvalLeavesSuspendedAccountsUntouched() {
        long suspended = employee("EMPLOYEE", "SUSPENDED");
        jdbc.update("INSERT INTO branch_memberships (employee_id,branch_id,role,status) VALUES (?,?,'STAFF','PENDING')", suspended, branch);
        long version = version(suspended);
        accounts.activateInvitationMembership(suspended, branch, owner, List.of(), "approve");
        assertThat(jdbc.queryForObject("SELECT status FROM employees WHERE id=?", String.class, suspended)).isEqualTo("SUSPENDED");
        assertThat(version(suspended)).isEqualTo(version);
    }

    @Test
    void disablingAnAccountDeletesItsSessionsInTheSameTransaction() {
        String session = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO SPRING_SESSION (PRIMARY_ID,SESSION_ID,CREATION_TIME,LAST_ACCESS_TIME,MAX_INACTIVE_INTERVAL,EXPIRY_TIME,"
                + "PRINCIPAL_NAME) VALUES (?,?,0,0,3600,4102444800000,?)", session, session, Long.toString(employee));
        long version = version(employee);
        var updated = management.update(owner, employee, Map.of("version", version + 1, "status", "비활성"), "disable");
        assertThat(updated.status()).isEqualTo("비활성");
        assertThat(updated.version()).isEqualTo(version + 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME=?", Long.class, Long.toString(employee))).isZero();
    }

    private EmployeeAccount former(long id) {
        return jdbc.query("SELECT * FROM employees WHERE id = ?", (rs, row) -> new EmployeeAccount(
                rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"), rs.getString("name"),
                rs.getString("nickname"), rs.getString("avatar_url"), rs.getString("global_role"),
                rs.getString("status"), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), id).getFirst();
    }

    private List<EmployeeLookup> formerLookup(long branchId) {
        return jdbc.query("SELECT e.id,e.name FROM employees e WHERE e.status='ACTIVE' AND "
                + "(e.global_role='OWNER' OR EXISTS (SELECT 1 FROM branch_memberships m WHERE m.employee_id=e.id "
                + "AND m.status='ACTIVE' AND m.branch_id IN (?))) ORDER BY e.id",
                (rs, row) -> new EmployeeLookup(rs.getString("id"), rs.getString("name")), branchId);
    }

    private long version(long id) { return jdbc.queryForObject("SELECT version FROM employees WHERE id=?", Long.class, id); }

    private long employee(String role, String status) {
        String email = "employee-jpa-" + UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO employees (email,password_hash,name,nickname,global_role,status) VALUES (?,'hash','직원','직원',?,?)",
                email, role, status);
        return jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
    }
}
