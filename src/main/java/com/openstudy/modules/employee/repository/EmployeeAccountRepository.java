package com.openstudy.modules.employee.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.openstudy.modules.employee.dto.EmployeeAccount;
import com.openstudy.modules.employee.dto.EmployeeMembership;

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

    public EmployeeAccount lockById(long id) {
        return jdbc.queryForObject("SELECT * FROM employees WHERE id = ? FOR UPDATE", ACCOUNT, id);
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
