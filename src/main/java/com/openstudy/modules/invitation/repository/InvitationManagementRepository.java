package com.openstudy.modules.invitation.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.idempotency.IdempotencyRecord;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.persistence.DuplicateKeys;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.modules.authorization.service.PermissionCatalog;
import com.openstudy.modules.branch.service.SessionBranchService;
import com.openstudy.modules.invitation.dto.Invitation;
import com.openstudy.modules.invitation.entity.EmployeeInvitation;
import com.openstudy.modules.invitation.entity.InitialRole;
import com.openstudy.modules.invitation.entity.InvitationSendJob;
import com.openstudy.modules.invitation.entity.InvitationStatus;

/**
 * Invitation management and acceptance persistence.
 * <p>Reads take entity row locks and return value snapshots, as the former JDBC reads did; every transition is a JPQL
 * statement that mirrors the former one, so versions, revisions and DB-side timestamps change exactly as before.
 * Grants are stored as permission ids and branch names come from the branch module (JPA-03).</p>
 */
@Repository
@RequiredArgsConstructor
public class InvitationManagementRepository {

    private static final String CREATE_OPERATION = "createInvitation";
    private static final String AUDIT_ENTITY_TYPE = "invitations";
    private static final int CREATED_STATUS = 201;
    private static final int FIRST_REVISION = 1;
    private static final Set<InvitationStatus> ACTIVE = EnumSet.of(InvitationStatus.PENDING, InvitationStatus.ACCEPTED);

    private final InvitationRepository invitationRepository;
    private final InvitationSendJobRepository invitationSendJobRepository;
    private final PermissionCatalog permissionCatalog;
    private final SessionBranchService sessionBranchService;
    private final ObjectMapper objectMapper;
    private final AuditTrail auditTrail;
    private final IdempotencyStore idempotencyStore;

    public record Stored(
            long id,
            long branch,
            String email,
            String role,
            String state,
            long revision,
            long version,
            Instant expiresAt,
            Long acceptedEmployeeId,
            Invitation view
    ) {
    }

    /** Server-side view of a link holder's invitation. The token hash never leaves this layer. */
    public record Link(
            long id,
            long branch,
            String branchName,
            String name,
            String email,
            String role,
            String state,
            Instant expiresAt,
            byte[] tokenHash
    ) {
    }

    /** Locked: the invitation FOR UPDATE, then its grant rows and current-revision job FOR SHARE, as before. */
    public Optional<Stored> find(long id, boolean lock, Instant now) {
        Optional<EmployeeInvitation> found = lock
                ? invitationRepository.findForUpdateById(id)
                : invitationRepository.findById(id);

        if (found.isEmpty()) {
            return Optional.empty();
        }

        EmployeeInvitation row = found.get();
        List<Long> grantIds = lock
                ? invitationRepository.findPermissionIdsForShare(id).stream().map(Number::longValue).toList()
                : invitationRepository.findPermissionIds(id);
        List<String> grants = permissionCatalog.codes(grantIds);
        Optional<InvitationSendJob> delivery = lock
                ? invitationSendJobRepository.findSharedByInvitationIdAndRevision(id, row.getRevision())
                : invitationSendJobRepository.findByInvitationIdAndRevision(id, row.getRevision());

        String state = row.getStatus().name();
        Invitation view = toView(row, grants, delivery, state, now);

        return Optional.of(new Stored(
                id,
                row.getBranchId(),
                row.getInviteeEmail(),
                row.getInitialRole().name(),
                state,
                row.getRevision(),
                row.getVersion(),
                row.getExpiresAt(),
                row.getAcceptedEmployeeId(),
                view
        ));
    }

    public List<Long> ids(Set<Long> branches) {
        if (branches.isEmpty()) {
            return List.of();
        }

        return invitationRepository.findIdsByBranchIdIn(branches);
    }

    public void lock(long id) {
        invitationRepository.findForUpdateById(id);
    }

    public void lockCandidates(long branch, String email) {
        invitationRepository.findForUpdateByBranchIdAndInviteeEmailAndStatusInOrderByIdAsc(branch, email, ACTIVE);
    }

    public List<Stored> active(long branch, String email, Instant now) {
        return invitationRepository.findForUpdateByBranchIdAndInviteeEmailAndStatusInOrderByIdAsc(branch, email, ACTIVE)
                .stream()
                .map(invitation -> find(invitation.getId(), true, now).orElseThrow())
                .toList();
    }

    /** Unknown permission codes are skipped, as the former INSERT ... SELECT by code skipped them. */
    public long create(
            long actor,
            long branch,
            String name,
            String email,
            String role,
            Long preset,
            List<String> permissionCodes,
            Instant expires
    ) {
        // The service maps a duplicate active invitation (uk_employee_invitations_1) to DUPLICATE_INVITATION.
        EmployeeInvitation invitation = DuplicateKeys.translate(() -> invitationRepository.saveAndFlush(
                EmployeeInvitation.create(
                        branch,
                        name,
                        email,
                        InitialRole.valueOf(role),
                        preset,
                        permissionCatalog.ids(permissionCodes),
                        expires,
                        actor
                )
        ));
        long id = invitation.getId();
        invitationSendJobRepository.enqueue(id, FIRST_REVISION);

        return id;
    }

    public void expire(long actor, Stored invitation) {
        invitationRepository.expireBy(invitation.id(), actor);
        invitationSendJobRepository.skipOpenJobs(invitation.id());
    }

    public void reissue(long actor, Stored invitation, Instant expires) {
        invitationSendJobRepository.skipOpenJobs(invitation.id());
        invitationRepository.reissue(invitation.id(), actor, expires);
        invitationSendJobRepository.enqueue(invitation.id(), Math.toIntExact(invitation.revision() + 1));
    }

    public void cancel(long actor, long id) {
        invitationRepository.cancel(id, actor);
        invitationSendJobRepository.skipOpenJobs(id);
    }

    public void reject(long actor, long id, String reason) {
        invitationRepository.reject(id, actor, reason);
        invitationSendJobRepository.skipOpenJobs(id);
    }

    public Optional<Link> link(byte[] tokenHash) {
        return invitationRepository.findIdsByTokenHash(tokenHash)
                .stream()
                .findFirst()
                .flatMap(id -> link(id, false));
    }

    /**
     * The former statement joined branches for the name. Callers that lock already hold every branch row through
     * AuthorizedCommands, so the name now comes from the branch module's session contract without a join.
     */
    public Optional<Link> link(long id, boolean lock) {
        Optional<EmployeeInvitation> found = lock
                ? invitationRepository.findForUpdateById(id)
                : invitationRepository.findById(id);

        return found.map(this::toLink);
    }

    public void accept(long employee, long id) {
        invitationRepository.accept(id, employee);
        invitationSendJobRepository.skipOpenJobs(id);
    }

    public void approve(long actor, long id) {
        invitationRepository.approve(id, actor);
    }

    public void changed(
            long actor,
            long branch,
            long id,
            String action,
            Invitation before,
            Invitation after,
            String requestId
    ) {
        String beforeJson = before == null ? null : objectMapper.writeValueAsString(before);
        AuditEvent event = AuditEvent.change(
                actor,
                branch,
                AUDIT_ENTITY_TYPE,
                id,
                action,
                beforeJson,
                objectMapper.writeValueAsString(after),
                requestId
        );

        auditTrail.record(event);
    }

    public Optional<Invitation> replay(long actor, long branch, String key, Object input, Instant now) {
        Optional<IdempotencyRecord> stored = idempotencyStore.lock(actor, CREATE_OPERATION, Long.toString(branch), key);

        if (stored.isEmpty()) {
            return Optional.empty();
        }

        IdempotencyRecord prior = stored.get();

        if (!now.isBefore(prior.getExpiresAt())) {
            idempotencyStore.discard(prior);
            return Optional.empty();
        }

        if (!MessageDigest.isEqual(hash(input), prior.getRequestHash())) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키에 다른 입력을 사용할 수 없습니다.");
        }

        return Optional.of(objectMapper.readValue(prior.getResponseJson(), Invitation.class));
    }

    public void remember(long actor, long branch, String key, Object input, Invitation response, Instant expires) {
        idempotencyStore.complete(
                actor,
                CREATE_OPERATION,
                Long.toString(branch),
                key,
                hash(input),
                CREATED_STATUS,
                objectMapper.writeValueAsString(response),
                expires
        );
    }

    private Link toLink(EmployeeInvitation invitation) {
        long branchId = invitation.getBranchId();
        String branchName = sessionBranchService.names(List.of(branchId)).get(branchId);

        return new Link(
                invitation.getId(),
                branchId,
                branchName,
                invitation.getInviteeName(),
                invitation.getInviteeEmail(),
                invitation.getInitialRole().name(),
                invitation.getStatus().name(),
                invitation.getExpiresAt(),
                invitation.getTokenHash()
        );
    }

    private byte[] hash(Object input) {
        byte[] json = objectMapper.writeValueAsString(input).getBytes(StandardCharsets.UTF_8);

        try {
            return MessageDigest.getInstance("SHA-256").digest(json);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Invitation toView(
            EmployeeInvitation row,
            List<String> grants,
            Optional<InvitationSendJob> delivery,
            String state,
            Instant now
    ) {
        boolean expired = "PENDING".equals(state) && !now.isBefore(row.getExpiresAt());
        String deliveryState = delivery.map(job -> job.getStatus().name()).orElse("SKIPPED");
        String employeeId = row.getAcceptedEmployeeId() == null ? null : row.getAcceptedEmployeeId().toString();

        return new Invitation(
                Long.toString(row.getId()),
                ApiVersion.fromDatabase(row.getVersion()),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                Long.toString(row.getBranchId()),
                row.getInviteeName(),
                row.getInviteeEmail(),
                row.getInitialRole() == InitialRole.MANAGER ? "admin" : "staff",
                grants,
                toStatusLabel(expired ? "EXPIRED" : state),
                toDeliveryLabel(deliveryState),
                row.getExpiresAt(),
                employeeId,
                row.getRejectionReason()
        );
    }

    private static String toStatusLabel(String state) {
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

    private static String toDeliveryLabel(String state) {
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
