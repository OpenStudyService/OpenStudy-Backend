package com.openstudy.modules.invitation.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.outbox.WorkspaceOutbox;
import com.openstudy.common.persistence.DuplicateKeys;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.modules.authorization.service.PermissionCatalog;
import com.openstudy.modules.branch.service.SessionBranchService;
import com.openstudy.modules.invitation.dto.Invitation;
import com.openstudy.modules.invitation.entity.EmployeeInvitation;
import com.openstudy.modules.invitation.entity.InitialRole;
import com.openstudy.modules.invitation.entity.InvitationStatus;

/**
 * Invitation management and acceptance persistence.
 * <p>Reads take entity row locks and return value snapshots, as the former JDBC reads did; every transition is a JPQL
 * statement that mirrors the former one, so versions, revisions and DB-side timestamps change exactly as before.
 * Grants are stored as permission ids and branch names come from the branch module (JPA-03).</p>
 */
@Repository
public class InvitationManagementRepository {
    private static final String CREATE = "createInvitation";
    private static final Set<InvitationStatus> ACTIVE = EnumSet.of(InvitationStatus.PENDING, InvitationStatus.ACCEPTED);
    private final InvitationRepository invitations;
    private final InvitationSendJobRepository jobs;
    private final PermissionCatalog permissions;
    private final SessionBranchService branches;
    private final ObjectMapper json;
    private final AuditTrail audit;
    private final WorkspaceOutbox outbox;
    private final IdempotencyStore requests;

    public InvitationManagementRepository(InvitationRepository invitations, InvitationSendJobRepository jobs,
            PermissionCatalog permissions, SessionBranchService branches, ObjectMapper json, AuditTrail audit,
            WorkspaceOutbox outbox, IdempotencyStore requests) {
        this.invitations = invitations;
        this.jobs = jobs;
        this.permissions = permissions;
        this.branches = branches;
        this.json = json;
        this.audit = audit;
        this.outbox = outbox;
        this.requests = requests;
    }

    public record Stored(long id, long branch, String email, String role, String state, long revision,
            long version, Instant expiresAt, Long acceptedEmployeeId, Invitation view) {}

    /** Locked: the invitation FOR UPDATE, then its grant rows and current-revision job FOR SHARE, as before. */
    public Optional<Stored> find(long id, boolean lock, Instant now) {
        var found = lock ? invitations.findForUpdateById(id) : invitations.findById(id);
        if (found.isEmpty()) return Optional.empty();
        var row = found.get();
        var grantIds = lock ? invitations.lockedPermissionIds(id).stream().map(Number::longValue).toList()
                : invitations.permissionIds(id);
        var grants = permissions.codes(grantIds);
        var delivery = lock ? jobs.findSharedByInvitationIdAndRevision(id, row.getRevision())
                : jobs.findByInvitationIdAndRevision(id, row.getRevision());
        String state = row.getStatus().name();
        var result = new Invitation(Long.toString(id), ApiVersion.fromDatabase(row.getVersion()), row.getCreatedAt(),
                row.getUpdatedAt(), Long.toString(row.getBranchId()), row.getInviteeName(), row.getInviteeEmail(),
                row.getInitialRole() == InitialRole.MANAGER ? "admin" : "staff", grants,
                status("PENDING".equals(state) && !now.isBefore(row.getExpiresAt()) ? "EXPIRED" : state),
                delivery(delivery.map(job -> job.getStatus().name()).orElse("SKIPPED")), row.getExpiresAt(),
                row.getAcceptedEmployeeId() == null ? null : row.getAcceptedEmployeeId().toString(), row.getRejectionReason());
        return Optional.of(new Stored(id, row.getBranchId(), row.getInviteeEmail(), row.getInitialRole().name(), state,
                row.getRevision(), row.getVersion(), row.getExpiresAt(), row.getAcceptedEmployeeId(), result));
    }

    public List<Long> ids(Set<Long> branches) {
        if (branches.isEmpty()) return List.of();
        return invitations.findIdsByBranchIdIn(branches);
    }

    public void lock(long id) {
        invitations.findForUpdateById(id);
    }

    public void lockCandidates(long branch, String email) {
        invitations.findForUpdateByBranchIdAndInviteeEmailAndStatusInOrderByIdAsc(branch, email, ACTIVE);
    }

    public List<Stored> active(long branch, String email, Instant now) {
        return invitations.findForUpdateByBranchIdAndInviteeEmailAndStatusInOrderByIdAsc(branch, email, ACTIVE).stream()
                .map(invitation -> find(invitation.getId(), true, now).orElseThrow()).toList();
    }

    /** Unknown permission codes are skipped, as the former INSERT ... SELECT by code skipped them. */
    public long create(long actor, long branch, String name, String email, String role, Long preset,
            List<String> permissionCodes, Instant expires) {
        // The service maps a duplicate active invitation (uk_employee_invitations_1) to DUPLICATE_INVITATION.
        var invitation = DuplicateKeys.translate(() -> invitations.saveAndFlush(EmployeeInvitation.create(branch, name, email,
                InitialRole.valueOf(role), preset, permissions.ids(permissionCodes), expires, actor)));
        long id = invitation.getId();
        jobs.enqueue(id, 1);
        return id;
    }

    public void expire(long actor, Stored invitation) {
        invitations.expireBy(invitation.id(), actor);
        jobs.skipOpenJobs(invitation.id());
    }

    public void reissue(long actor, Stored invitation, Instant expires) {
        jobs.skipOpenJobs(invitation.id());
        invitations.reissue(invitation.id(), actor, expires);
        jobs.enqueue(invitation.id(), Math.toIntExact(invitation.revision() + 1));
    }

    public void cancel(long actor, long id) {
        invitations.cancel(id, actor);
        jobs.skipOpenJobs(id);
    }

    public void reject(long actor, long id, String reason) {
        invitations.reject(id, actor, reason);
        jobs.skipOpenJobs(id);
    }

    /** Server-side view of a link holder's invitation. The token hash never leaves this layer. */
    public record Link(long id, long branch, String branchName, String name, String email, String role,
            String state, Instant expiresAt, byte[] tokenHash) {}

    public Optional<Link> link(byte[] tokenHash) {
        return invitations.findIdsByTokenHash(tokenHash).stream().findFirst().flatMap(id -> link(id, false));
    }

    /**
     * The former statement joined branches for the name. Callers that lock already hold every branch row through
     * AuthorizedCommands, so the name now comes from the branch module's session contract without a join.
     */
    public Optional<Link> link(long id, boolean lock) {
        return (lock ? invitations.findForUpdateById(id) : invitations.findById(id)).map(i -> new Link(i.getId(), i.getBranchId(),
                branches.names(List.of(i.getBranchId())).get(i.getBranchId()), i.getInviteeName(), i.getInviteeEmail(),
                i.getInitialRole().name(), i.getStatus().name(), i.getExpiresAt(), i.getTokenHash()));
    }

    public void accept(long employee, long id) {
        invitations.accept(id, employee);
        jobs.skipOpenJobs(id);
    }

    public void approve(long actor, long id) {
        invitations.approve(id, actor);
    }

    public void changed(long actor, long branch, long id, String action, Invitation before, Invitation after, String requestId) {
        audit.record(AuditEvent.change(actor, branch, "invitations", id, action,
                before == null ? null : json.writeValueAsString(before), json.writeValueAsString(after), requestId));
        outbox.invalidate(branch, "invitations", id);
    }

    public Optional<Invitation> replay(long actor, long branch, String key, Object input, Instant now) {
        var stored = requests.lock(actor, CREATE, Long.toString(branch), key);
        if (stored.isEmpty()) return Optional.empty();
        var prior = stored.get();
        if (!now.isBefore(prior.getExpiresAt())) {
            requests.discard(prior);
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(hash(input), prior.getRequestHash())) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키에 다른 입력을 사용할 수 없습니다.");
        }
        return Optional.of(json.readValue(prior.getResponseJson(), Invitation.class));
    }

    public void remember(long actor, long branch, String key, Object input, Invitation response, Instant expires) {
        requests.complete(actor, CREATE, Long.toString(branch), key, hash(input), 201, json.writeValueAsString(response), expires);
    }

    private byte[] hash(Object input) {
        try { return MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(input).getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String status(String state) {
        return switch (state) {
            case "PENDING" -> "초대 대기";
            case "ACCEPTED" -> "승인 대기";
            case "APPROVED" -> "활성";
            case "CANCELLED" -> "취소";
            case "REJECTED" -> "거절";
            case "EXPIRED" -> "만료";
            default -> throw new IllegalStateException("Unknown invitation state");
        };
    }

    private static String delivery(String state) {
        return switch (state) {
            case "PENDING" -> "발송 대기";
            case "PROCESSING" -> "발송 중";
            case "SENT" -> "발송 완료";
            case "FAILED" -> "발송 실패";
            case "SKIPPED" -> "발송 생략";
            default -> throw new IllegalStateException("Unknown delivery state");
        };
    }
}
