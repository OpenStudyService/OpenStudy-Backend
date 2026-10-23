package com.openstudy.modules.branch.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.openstudy.modules.branch.dto.SessionBranch;

@Repository
public class SessionBranchRepository {
    private final JdbcTemplate jdbc;

    public SessionBranchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SessionBranch> accessible(long employeeId, boolean owner) {
        return jdbc.query("SELECT b.* FROM branches b WHERE b.status = 'ACTIVE' AND (? OR EXISTS "
                + "(SELECT 1 FROM branch_memberships m WHERE m.branch_id = b.id AND m.employee_id = ? "
                + "AND m.status = 'ACTIVE')) ORDER BY b.id", (rs, row) -> new SessionBranch(
                        rs.getString("id"), rs.getString("id"), rs.getLong("version") + 1,
                        rs.getTimestamp("created_at").toInstant().toString(), rs.getTimestamp("updated_at").toInstant().toString(),
                        rs.getString("name"), rs.getString("address"), rs.getString("phone") == null ? "" : rs.getString("phone"),
                        "", "운영 중"), owner, employeeId);
    }
}
