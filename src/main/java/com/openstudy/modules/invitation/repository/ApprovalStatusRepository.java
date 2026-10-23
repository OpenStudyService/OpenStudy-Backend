package com.openstudy.modules.invitation.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.openstudy.modules.invitation.dto.ApprovalStatus;

@Repository
public class ApprovalStatusRepository {
    private final JdbcTemplate jdbc;
    public ApprovalStatusRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<ApprovalStatus.Item> acceptedBy(long employeeId) {
        return jdbc.query("SELECT i.id,i.branch_id,b.name,i.status,i.rejection_reason,i.updated_at "
                + "FROM employee_invitations i JOIN branches b ON b.id=i.branch_id "
                + "WHERE i.accepted_employee_id=? AND i.accepted_at IS NOT NULL "
                + "AND i.status IN ('ACCEPTED','APPROVED','REJECTED','CANCELLED') ORDER BY i.id", (rs, row) ->
                        new ApprovalStatus.Item(rs.getString("id"), rs.getString("branch_id"), rs.getString("name"),
                                switch (rs.getString("status")) {
                                    case "ACCEPTED" -> "승인 대기";
                                    case "APPROVED" -> "활성";
                                    case "REJECTED" -> "거절";
                                    case "CANCELLED" -> "취소";
                                    default -> throw new IllegalStateException("Invalid accepted invitation status");
                                }, rs.getString("rejection_reason"), rs.getTimestamp("updated_at").toInstant().toString()), employeeId);
    }
}
