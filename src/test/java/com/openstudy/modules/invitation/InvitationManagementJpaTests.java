package com.openstudy.modules.invitation;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.common.web.ApiVersion;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.invitation.dto.Invitation;
import com.openstudy.modules.invitation.repository.InvitationManagementRepository;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 6-2: invitation management and acceptance persistence moved to JPA. Reads must equal the removed SQL (the
 * oracle below), and each transition must leave the rows the former statement left.
 */
@MySqlIntegrationTest
@Transactional
class InvitationManagementJpaTests {

    @Autowired InvitationManagementRepository invitations;
    @Autowired EmployeeAccountService employees;
    @Autowired JdbcTemplate jdbc;
    long actor;
    long branch;
    Instant now;

    @BeforeEach
    void seed() {
        actor = employee();
        String code = "IM-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO branches (code,name,address) VALUES (?,'초대 지점','주소')", code);
        branch = jdbc.queryForObject("SELECT id FROM branches WHERE code=?", Long.class, code);
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void createWritesTheFormerRowsAndReadsEqualTheFormerSql() {
        long id = invitations.create(actor, branch, "초대", email(), "MANAGER", null,
                List.of("sales:read", "members:write", "unknown:code"), now.plusSeconds(86400));
        assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("revision", 1L).containsEntry("version", 0L)
                .containsEntry("created_by", actor).containsEntry("updated_by", actor);
        assertThat(jobs(id)).containsExactly(Map.of("revision", 1L, "status", "PENDING", "attempts", 0L, "due_age_ok", 1L));

        for (boolean lock : List.of(false, true)) {
            assertThat(invitations.find(id, lock, now).orElseThrow().view()).isEqualTo(former(id, now));
        }
        assertThat(invitations.find(id, false, now).orElseThrow().view().permissions()).containsExactly("members:write", "sales:read");
        // Past its expiry a PENDING invitation reads as expired, without any write.
        assertThat(invitations.find(id, false, now.plusSeconds(90000)).orElseThrow().view()).isEqualTo(former(id, now.plusSeconds(90000)));
        assertThat(invitations.ids(java.util.Set.of(branch))).containsExactly(id);
    }

    @Test
    void reissueCancelAndRejectLeaveTheFormerRows() {
        long id = invitations.create(actor, branch, "초대", email(), "STAFF", null, List.of(), now.plusSeconds(3600));
        jdbc.update("UPDATE invitation_send_jobs SET status='FAILED',last_error_code='SMTP_FAILED' WHERE invitation_id=?", id);
        Instant expires = now.plusSeconds(86400);
        invitations.reissue(actor, invitations.find(id, true, now).orElseThrow(), expires);
        assertThat(row(id)).containsEntry("revision", 2L).containsEntry("version", 1L).containsEntry("status", "PENDING");
        assertThat(invitations.find(id, true, now).orElseThrow().expiresAt()).isEqualTo(expires);
        // Management closes jobs without overwriting their error code (the worker's expiry sets INVITATION_EXPIRED).
        assertThat(jdbc.queryForMap("SELECT status,last_error_code FROM invitation_send_jobs WHERE invitation_id=? AND revision=1", id))
                .containsEntry("status", "SKIPPED").containsEntry("last_error_code", "SMTP_FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM invitation_send_jobs WHERE invitation_id=? AND revision=2", String.class, id))
                .isEqualTo("PENDING");

        invitations.cancel(actor, id);
        assertThat(row(id)).containsEntry("status", "CANCELLED").containsEntry("cancelled_by", actor).containsEntry("version", 2L)
                .containsEntry("cancelled_age_ok", 1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invitation_send_jobs WHERE invitation_id=? AND status='PENDING'", Long.class, id)).isZero();

        long other = invitations.create(actor, branch, "거절", email(), "STAFF", null, List.of(), now.plusSeconds(3600));
        long invitee = employee();
        jdbc.update("UPDATE employee_invitations SET status='ACCEPTED',accepted_employee_id=?,accepted_at=UTC_TIMESTAMP(6) WHERE id=?", invitee, other);
        invitations.reject(actor, other, "확인 필요");
        assertThat(row(other)).containsEntry("status", "REJECTED").containsEntry("rejection_reason", "확인 필요").containsEntry("version", 1L);
        assertThat(invitations.find(other, false, now).orElseThrow().view()).isEqualTo(former(other, now));
    }

    @Test
    void acceptAndApproveOnlyChangeTheExpectedStateAndLinksResolveByToken() {
        long id = invitations.create(actor, branch, "수락", email(), "STAFF", null, List.of(), now.plusSeconds(3600));
        byte[] hash = new byte[32];
        hash[0] = 9; hash[31] = (byte) (id % 100);
        jdbc.update("UPDATE employee_invitations SET token_hash=? WHERE id=?", hash, id);
        var link = invitations.link(hash).orElseThrow();
        assertThat(link.id()).isEqualTo(id);
        assertThat(link.branchName()).isEqualTo("초대 지점");
        assertThat(link.tokenHash()).isEqualTo(hash);

        long invitee = employee();
        invitations.approve(actor, id); // not ACCEPTED yet: no change
        assertThat(row(id)).containsEntry("status", "PENDING").containsEntry("version", 0L);
        invitations.accept(invitee, id);
        assertThat(row(id)).containsEntry("status", "ACCEPTED").containsEntry("version", 1L).containsEntry("updated_by", invitee);
        assertThat(invitations.link(hash)).isEmpty();
        invitations.accept(invitee, id); // already ACCEPTED: no change
        assertThat(row(id)).containsEntry("version", 1L);
        invitations.approve(actor, id);
        assertThat(row(id)).containsEntry("status", "APPROVED").containsEntry("approved_by", actor).containsEntry("version", 2L);
    }

    @Test
    void duplicateInvitedAccountStillSurfacesAsDuplicateKeyException() {
        String email = jdbc.queryForObject("SELECT email FROM employees WHERE id=?", String.class, actor);
        // InvitationAcceptanceService maps this exception to "already registered"; JPA must keep its type.
        assertThatThrownBy(() -> employees.createInvitedAccount(email.toUpperCase(), "hash", "중복"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void duplicateActiveInvitationIsADuplicateKeyButOtherIntegrityErrorsAreNot() {
        String email = email();
        invitations.create(actor, branch, "첫 초대", email, "STAFF", null, List.of(), now.plusSeconds(3600));
        // InvitationManagementService maps this exception to DUPLICATE_INVITATION.
        assertThatThrownBy(() -> invitations.create(actor, branch, "둘째 초대", email, "STAFF", null, List.of(), now.plusSeconds(3600)))
                .isInstanceOf(DuplicateKeyException.class);
        // A CHECK violation (blank invitee name) stays a plain integrity error, as it was under JDBC.
        assertThatThrownBy(() -> invitations.create(actor, branch, " ", email(), "STAFF", null, List.of(), now.plusSeconds(3600)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateKeyException.class);
    }

    /** The removed JDBC find(), run against the same rows. */
    private Invitation former(long id, Instant at) {
        var row = jdbc.queryForMap("SELECT * FROM employee_invitations WHERE id=?", id);
        var grants = jdbc.queryForList("SELECT d.code FROM invitation_permission_grants g "
                + "JOIN permission_definitions d ON d.id=g.permission_id WHERE g.invitation_id=? ORDER BY d.code", String.class, id);
        var delivery = jdbc.queryForList("SELECT status FROM invitation_send_jobs WHERE invitation_id=? AND revision=?",
                String.class, id, row.get("revision"));
        String state = (String) row.get("status");
        Instant expires = instant(row.get("expires_at"));
        String shown = "PENDING".equals(state) && !at.isBefore(expires) ? "EXPIRED" : state;
        Object employee = row.get("accepted_employee_id");
        return new Invitation(Long.toString(id), ApiVersion.fromDatabase(((Number) row.get("version")).longValue()),
                instant(row.get("created_at")), instant(row.get("updated_at")), row.get("branch_id").toString(),
                (String) row.get("invitee_name"), (String) row.get("invitee_email"),
                "MANAGER".equals(row.get("initial_role")) ? "admin" : "staff", grants, status(shown),
                delivery(delivery.isEmpty() ? "SKIPPED" : delivery.getFirst()), expires,
                employee == null ? null : employee.toString(), (String) row.get("rejection_reason"));
    }

    private static Instant instant(Object value) {
        return value instanceof Timestamp t ? t.toInstant() : ((java.time.LocalDateTime) value).toInstant(java.time.ZoneOffset.UTC);
    }

    private static String status(String state) {
        return switch (state) {
            case "PENDING" -> "초대 대기"; case "ACCEPTED" -> "승인 대기"; case "APPROVED" -> "활성";
            case "CANCELLED" -> "취소"; case "REJECTED" -> "거절"; case "EXPIRED" -> "만료";
            default -> throw new IllegalStateException();
        };
    }

    private static String delivery(String state) {
        return switch (state) {
            case "PENDING" -> "발송 대기"; case "PROCESSING" -> "발송 중"; case "SENT" -> "발송 완료";
            case "FAILED" -> "발송 실패"; case "SKIPPED" -> "발송 생략";
            default -> throw new IllegalStateException();
        };
    }

    private Map<String, Object> row(long id) {
        return longs(jdbc.queryForMap("SELECT status,revision,version,created_by,updated_by,cancelled_by,approved_by,rejection_reason,"
                + "cancelled_at IS NOT NULL AND TIMESTAMPDIFF(SECOND,cancelled_at,UTC_TIMESTAMP(6)) BETWEEN 0 AND 60 cancelled_age_ok "
                + "FROM employee_invitations WHERE id=?", id));
    }

    private List<Map<String, Object>> jobs(long id) {
        return jdbc.queryForList("SELECT revision,status,attempts,TIMESTAMPDIFF(SECOND,next_attempt_at,UTC_TIMESTAMP(6)) BETWEEN 0 AND 60 due_age_ok "
                + "FROM invitation_send_jobs WHERE invitation_id=? ORDER BY revision", id).stream().map(InvitationManagementJpaTests::longs).toList();
    }

    private static Map<String, Object> longs(Map<String, Object> row) {
        var copy = new HashMap<String, Object>();
        row.forEach((k, v) -> copy.put(k, v instanceof Number n ? n.longValue() : v));
        return copy;
    }

    private long employee() {
        String email = email();
        jdbc.update("INSERT INTO employees (email,password_hash,name,nickname,global_role,status) VALUES (?,'hash','관리','관리','EMPLOYEE','ACTIVE')", email);
        return jdbc.queryForObject("SELECT id FROM employees WHERE email=?", Long.class, email);
    }

    private static String email() { return "invitation-jpa-" + UUID.randomUUID() + "@example.com"; }
}
