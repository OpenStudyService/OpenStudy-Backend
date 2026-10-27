package com.openstudy.integration.database;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.common.web.ApiVersion;
import com.openstudy.modules.branch.dto.SessionBranch;
import com.openstudy.modules.branch.service.SessionBranchService;
import com.openstudy.modules.invitation.dto.ApprovalStatus;
import com.openstudy.modules.invitation.service.ApprovalStatusService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2: the JPA reads must return exactly what the former JDBC queries returned.
 * Each oracle below is the removed SQL and row mapping, run against the same rows.
 */
@MySqlIntegrationTest
@Transactional
class JpaSimpleReadsTests {

    @Autowired SessionBranchService sessionBranches;
    @Autowired ApprovalStatusService approvals;
    @Autowired JdbcTemplate jdbc;
    long owner;
    long invitee;
    long other;
    long active;
    long suspended;
    long revoked;

    @BeforeEach
    void seed() {
        // Only an actor id for the invitation audit columns; the single-owner slot may already be taken.
        owner = employee("EMPLOYEE", "ACTIVE");
        invitee = employee("EMPLOYEE", "ACTIVE");
        other = employee("EMPLOYEE", "ACTIVE");
        active = branch("ACTIVE", "02-000-0000");
        suspended = branch("SUSPENDED", null);
        revoked = branch("ACTIVE", null);
        membership(invitee, active, "ACTIVE");
        membership(invitee, suspended, "ACTIVE");
        membership(invitee, revoked, "REVOKED");
        membership(other, active, "PENDING");
        jdbc.update("UPDATE branches SET version=version+3 WHERE id=?", active);

        invitation(active, invitee, "ACCEPTED");
        invitation(suspended, invitee, "REJECTED");
        invitation(revoked, invitee, "APPROVED");
        invitation(active, null, "PENDING");
        invitation(suspended, null, "CANCELLED");
        invitation(revoked, other, "ACCEPTED");
    }

    @Test
    void accessibleBranchesMatchTheFormerSql() {
        for (long employee : List.of(invitee, other)) {
            for (boolean isOwner : List.of(false, true)) {
                assertThat(sessionBranches.accessible(employee, isOwner)).isEqualTo(formerAccessible(employee, isOwner));
            }
        }
        assertThat(sessionBranches.accessible(invitee, false)).extracting(SessionBranch::id)
                .containsExactly(Long.toString(active), Long.toString(suspended));
        assertThat(sessionBranches.accessible(other, false)).isEmpty();
    }

    @Test
    void approvalStatusMatchesTheFormerSqlIncludingBranchNamesAndReasons() {
        for (long employee : List.of(invitee, other, owner)) {
            assertThat(approvals.current(employee, null).invitations()).isEqualTo(formerAcceptedBy(employee));
        }
        var items = approvals.current(invitee, null).invitations();
        assertThat(items).extracting(ApprovalStatus.Item::status).containsExactly("승인 대기", "거절", "활성");
        assertThat(items.get(1).rejectionReason()).isEqualTo("확인 필요");
        assertThat(items.get(0).branchName()).startsWith("지점-");
    }

    private List<SessionBranch> formerAccessible(long employeeId, boolean isOwner) {
        return jdbc.query("SELECT b.* FROM branches b WHERE (? OR EXISTS "
                + "(SELECT 1 FROM branch_memberships m WHERE m.branch_id = b.id AND m.employee_id = ? "
                + "AND m.status = 'ACTIVE')) ORDER BY b.id", (rs, row) -> new SessionBranch(
                        rs.getString("id"), rs.getString("id"), ApiVersion.fromDatabase(rs.getLong("version")),
                        rs.getTimestamp("created_at").toInstant().toString(), rs.getTimestamp("updated_at").toInstant().toString(),
                        rs.getString("name"), rs.getString("address"), rs.getString("phone") == null ? "" : rs.getString("phone"),
                        "", "ACTIVE".equals(rs.getString("status")) ? "운영 중" : "운영 중지"), isOwner, employeeId);
    }

    private List<ApprovalStatus.Item> formerAcceptedBy(long employeeId) {
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

    private long employee(String role, String status) {
        String email = "reads-" + UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO employees (email,password_hash,name,nickname,global_role,status) VALUES (?,'hash','읽기','읽기',?,?)",
                email, role, status);
        return jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
    }

    private long branch(String status, String phone) {
        String code = "RD-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO branches (code,name,address,phone,status) VALUES (?,?,'주소',?,?)", code, "지점-" + code, phone, status);
        return jdbc.queryForObject("SELECT id FROM branches WHERE code=?", Long.class, code);
    }

    private void membership(long employee, long branch, String status) {
        jdbc.update("INSERT INTO branch_memberships (employee_id,branch_id,role,status) VALUES (?,?,'STAFF',?)", employee, branch, status);
    }

    /** Fills exactly the columns each status requires by the ck_invitations_* constraints. */
    private void invitation(long branch, Long accepted, String status) {
        boolean acceptedState = List.of("ACCEPTED", "APPROVED", "REJECTED").contains(status);
        jdbc.update("INSERT INTO employee_invitations (branch_id,invitee_email,invitee_name,initial_role,created_by,status,"
                + "expires_at,accepted_employee_id,accepted_at,approved_by,approved_at,rejected_by,rejected_at,rejection_reason,"
                + "cancelled_by,cancelled_at) VALUES (?,?,'초대','STAFF',?,?,UTC_TIMESTAMP(6)+INTERVAL 1 DAY,?,"
                + (acceptedState ? "UTC_TIMESTAMP(6)" : "NULL") + ",?," + ("APPROVED".equals(status) ? "UTC_TIMESTAMP(6)" : "NULL")
                + ",?," + ("REJECTED".equals(status) ? "UTC_TIMESTAMP(6)" : "NULL") + ",?,?,"
                + ("CANCELLED".equals(status) ? "UTC_TIMESTAMP(6)" : "NULL") + ")",
                branch, "inv-" + UUID.randomUUID() + "@example.com", owner, status, acceptedState ? accepted : null,
                "APPROVED".equals(status) ? owner : null, "REJECTED".equals(status) ? owner : null,
                "REJECTED".equals(status) ? "확인 필요" : null, "CANCELLED".equals(status) ? owner : null);
    }
}
