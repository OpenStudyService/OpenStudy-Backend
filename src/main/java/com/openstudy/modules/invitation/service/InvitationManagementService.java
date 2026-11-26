package com.openstudy.modules.invitation.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.authorization.service.PermissionPresetService;
import com.openstudy.modules.employee.dto.InvitationTarget;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.invitation.dto.Invitation;
import com.openstudy.modules.invitation.repository.InvitationManagementRepository;
import com.openstudy.modules.invitation.repository.InvitationManagementRepository.Stored;

@Service
@RequiredArgsConstructor
public class InvitationManagementService {

    private static final Duration LIFETIME = Duration.ofHours(24);
    private static final Set<String> STATUS_LABELS = Set.of("초대 대기", "승인 대기", "활성", "취소", "거절", "만료");
    private static final Set<String> REISSUABLE_STATES = Set.of("PENDING", "EXPIRED");
    private static final Set<String> APPROVABLE_ACCOUNT_STATUSES = Set.of("PENDING", "ACTIVE");
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_REASON_LENGTH = 500;
    private static final int MIN_KEY_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MIN_KEY_CHAR = 33;
    private static final int MAX_KEY_CHAR = 126;

    private final InvitationManagementRepository invitationManagementRepository;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final PermissionPresetService permissionPresetService;
    private final EmployeeAccountService employeeAccountService;
    private final Clock clock;
    private final Validator validator;

    @Transactional(readOnly = true)
    public PageResponse<Invitation> list(long actor, String scope, ListQuery query, String status) {
        if (status != null && !STATUS_LABELS.contains(status)) {
            throw ApiException.validation("status", "올바른 초대 상태를 선택해 주세요.");
        }

        Set<Long> visible = findVisibleBranches(actor, scope);
        Instant now = clock.instant();
        String keyword = query.q().toLowerCase(Locale.ROOT);

        List<Invitation> rows = invitationManagementRepository.ids(visible)
                .stream()
                .map(id -> findStored(id, false, now).view())
                .filter(invitation -> status == null || status.equals(invitation.status()))
                .filter(invitation -> matchesKeyword(invitation, keyword))
                .toList();

        Comparator<Invitation> order = switch (query.sort()) {
            case "name" -> Comparator.comparing(Invitation::name);
            case "expiresAt" -> Comparator.comparing(Invitation::expiresAt);
            default -> Comparator.comparing(Invitation::createdAt);
        };
        Comparator<Invitation> tieBreaker = Comparator.comparingLong(invitation -> Long.parseLong(invitation.id()));

        return PageResponse.fromAuthorized(rows, query, order, tieBreaker);
    }

    @Transactional(readOnly = true)
    public Invitation get(long actor, long id, String scope) {
        Set<Long> visible = findVisibleBranches(actor, scope);

        if (!invitationManagementRepository.ids(visible).contains(id)) {
            throw notFound();
        }

        return findStored(id, false, clock.instant()).view();
    }

    public Invitation create(long actor, String scope, Map<String, Object> body, String key, String requestId) {
        StrictInput input = new StrictInput(body).only("branchId", "name", "email", "role", "presetId", "permissions");
        long branch = input.id("branchId");
        String name = input.text("name", MAX_NAME_LENGTH);
        String email = input.text("email", MAX_EMAIL_LENGTH).toLowerCase(Locale.ROOT);

        if (!validator.validate(new EmailAddress(email)).isEmpty()) {
            throw ApiException.validation("email", "올바른 이메일을 입력해 주세요.");
        }

        String role = input.choice("role", "admin", "staff");
        String databaseRole = "admin".equals(role) ? "MANAGER" : "STAFF";
        List<String> permissions = input.permissions();
        Long preset = input.has("presetId") ? input.id("presetId") : null;
        requireValidKey(key);
        requireSelectedScope(scope, branch);

        return authorizedCommands.inBranches(
                actor,
                Set.of(branch),
                BranchAction.MANAGE_PEOPLE,
                () -> invitationManagementRepository.lockCandidates(branch, email),
                state -> {
                    Instant now = clock.instant();
                    Optional<InvitationTarget> target = employeeAccountService.invitationTarget(email, branch);
                    requireTarget(state, branch, databaseRole, target);

                    // Check current actor and current target role before replaying a previously authorized result.
                    List<Object> fingerprint = Arrays.asList(branch, name, email, role, preset, permissions);
                    Optional<Invitation> replayed =
                            invitationManagementRepository.replay(actor, branch, key, fingerprint, now);

                    if (replayed.isPresent()) {
                        return replayed.get();
                    }

                    permissionPresetService.validateCopy(role, permissions, preset);
                    requireNoActiveMembership(target);
                    expireAndCheckDuplicates(actor, branch, email, null, now, requestId);

                    try {
                        Instant expires = now.plus(LIFETIME);
                        long id = invitationManagementRepository.create(
                                actor,
                                branch,
                                name,
                                email,
                                databaseRole,
                                preset,
                                permissions,
                                expires
                        );
                        Invitation result = findStored(id, true, now).view();
                        invitationManagementRepository.changed(actor, branch, id, "CREATED", null, result, requestId);
                        invitationManagementRepository.remember(actor, branch, key, fingerprint, result, expires);
                        return result;
                    } catch (DuplicateKeyException exception) {
                        throw duplicateInvitation();
                    }
                }
        );
    }

    public Invitation reissue(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("version");
        long version = input.version("version");

        return change(actor, id, scope, (state, before, target, now) -> {
            ApiVersion.requireCurrent(version, before.version(), toReloadPath(id));

            if (!REISSUABLE_STATES.contains(before.state())) {
                throw invalidState();
            }

            requireNoActiveMembership(target);
            expireAndCheckDuplicates(actor, before.branch(), before.email(), id, now, requestId);
            invitationManagementRepository.reissue(actor, before, now.plus(LIFETIME));

            Invitation result = findStored(id, true, now).view();
            recordChange(actor, before, "REISSUED", result, requestId);
            return result;
        });
    }

    public Invitation cancel(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("version");
        long version = input.version("version");

        return change(actor, id, scope, (state, before, target, now) -> {
            if ("CANCELLED".equals(before.state())) {
                return before.view();
            }

            ApiVersion.requireCurrent(version, before.version(), toReloadPath(id));
            boolean cancellable = "PENDING".equals(before.state()) && now.isBefore(before.expiresAt());

            if (!cancellable) {
                throw invalidState();
            }

            invitationManagementRepository.cancel(actor, id);

            Invitation result = findStored(id, true, now).view();
            recordChange(actor, before, "CANCELLED", result, requestId);
            return result;
        });
    }

    public Invitation reject(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("version", "reason");
        long version = input.version("version");
        String reason = input.text("reason", MAX_REASON_LENGTH);

        return change(actor, id, scope, (state, before, target, now) -> {
            if ("REJECTED".equals(before.state())) {
                return before.view();
            }

            ApiVersion.requireCurrent(version, before.version(), toReloadPath(id));

            if (!"ACCEPTED".equals(before.state())) {
                throw invalidState();
            }

            InvitationTarget account = requireAcceptedBy(target, before);
            boolean inconsistentPending = "PENDING".equals(account.membershipStatus())
                    && !before.role().equals(account.membershipRole());

            if (inconsistentPending) {
                // Acceptance owns a pending membership with the invitation's planned role. Fail closed
                // if a different workflow has left an inconsistent pending membership in this slot.
                throw invalidState();
            }

            employeeAccountService.revokePendingInvitationMembership(
                    account.employeeId(),
                    before.branch(),
                    actor,
                    requestId
            );
            invitationManagementRepository.reject(actor, id, reason);

            Invitation result = findStored(id, true, now).view();
            recordChange(actor, before, "REJECTED", result, requestId);
            return result;
        });
    }

    public Invitation approve(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        StrictInput input = new StrictInput(body).only("version");
        long version = input.version("version");

        return change(actor, id, scope, (state, before, target, now) -> {
            if ("APPROVED".equals(before.state())) {
                return before.view();
            }

            ApiVersion.requireCurrent(version, before.version(), toReloadPath(id));

            if (!"ACCEPTED".equals(before.state())) {
                throw invalidState();
            }

            InvitationTarget account = requireAcceptedBy(target, before);

            // Approval activates a PENDING account only. SUSPENDED/LEFT accounts are restored by the OWNER
            // account command (Q3), never as a side effect of an invitation (Q11).
            if (!APPROVABLE_ACCOUNT_STATUSES.contains(account.accountStatus())) {
                throw invalidState();
            }

            boolean pendingWithPlannedRole = "PENDING".equals(account.membershipStatus())
                    && before.role().equals(account.membershipRole());

            if (!pendingWithPlannedRole) {
                throw invalidState();
            }

            // The snapshot was valid at creation; re-check it against the current code dictionary and role policy.
            Invitation view = before.view();
            permissionPresetService.validateCopy(view.role(), view.permissions(), null);
            employeeAccountService.activateInvitationMembership(
                    account.employeeId(),
                    before.branch(),
                    actor,
                    view.permissions(),
                    requestId
            );
            invitationManagementRepository.approve(actor, id);

            Invitation result = findStored(id, true, now).view();
            recordChange(actor, before, "APPROVED", result, requestId);
            return result;
        });
    }

    private Invitation change(long actor, long id, String scope, Change command) {
        // Branch identity is immutable. Never trust a body/query branch for an existing invitation.
        // Apply the same visibility rule as detail before resolving an immutable parent branch.
        // A guessed ID outside the actor's scope must not become an existence oracle on writes.
        long branch = Long.parseLong(get(actor, id, scope).branchId());

        return authorizedCommands.inBranches(
                actor,
                Set.of(branch),
                BranchAction.MANAGE_PEOPLE,
                () -> invitationManagementRepository.lock(id),
                state -> {
                    Instant now = clock.instant();
                    Stored before = findStored(id, true, now);
                    Optional<InvitationTarget> target = employeeAccountService.invitationTarget(before.email(), branch);
                    requireTarget(state, branch, before.role(), target);

                    return command.apply(state, before, target, now);
                }
        );
    }

    private void requireTarget(AuthorizationState state, long branch, String role, Optional<InvitationTarget> target) {
        String targetRole = target.map(InvitationTarget::membershipRole).orElse(null);
        authorizationService.requireInvitationCommand(state, branch, role, targetRole);

        if (target.map(InvitationTarget::owner).orElse(false)) {
            throw AuthorizationService.forbidden();
        }
    }

    private Set<Long> findVisibleBranches(long actor, String scope) {
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> branches = authorizationService.scope(state, scope, BranchAction.MANAGE_PEOPLE);

        if (!state.owner() && branches.isEmpty()) {
            throw AuthorizationService.forbidden();
        }

        return branches;
    }

    private void expireAndCheckDuplicates(
            long actor,
            long branch,
            String email,
            Long except,
            Instant now,
            String requestId
    ) {
        for (Stored candidate : invitationManagementRepository.active(branch, email, now)) {
            if (except != null && candidate.id() == except) {
                continue;
            }

            boolean expired = "PENDING".equals(candidate.state()) && !now.isBefore(candidate.expiresAt());

            if (!expired) {
                throw duplicateInvitation();
            }

            invitationManagementRepository.expire(actor, candidate);
            Invitation after = findStored(candidate.id(), true, now).view();
            invitationManagementRepository.changed(
                    actor,
                    branch,
                    candidate.id(),
                    "EXPIRED",
                    candidate.view(),
                    after,
                    requestId
            );
        }
    }

    /** Records a change of an existing invitation, from its locked snapshot to the result. */
    private void recordChange(long actor, Stored before, String action, Invitation result, String requestId) {
        invitationManagementRepository.changed(
                actor,
                before.branch(),
                before.id(),
                action,
                before.view(),
                result,
                requestId
        );
    }

    private Stored findStored(long id, boolean lock, Instant now) {
        return invitationManagementRepository.find(id, lock, now).orElseThrow(InvitationManagementService::notFound);
    }

    /** The target must be the employee who accepted this invitation. */
    private static InvitationTarget requireAcceptedBy(Optional<InvitationTarget> target, Stored before) {
        boolean acceptedByTarget = target.isPresent()
                && Long.valueOf(target.get().employeeId()).equals(before.acceptedEmployeeId());

        if (!acceptedByTarget) {
            throw invalidState();
        }

        return target.get();
    }

    private static void requireNoActiveMembership(Optional<InvitationTarget> target) {
        if (target.isPresent() && "ACTIVE".equals(target.get().membershipStatus())) {
            throw ApiException.conflict("DUPLICATE_MEMBERSHIP", "이미 활성 소속이 있는 직원입니다.");
        }
    }

    private static boolean matchesKeyword(Invitation invitation, String keyword) {
        String searchable = invitation.name() + " " + invitation.email();

        return searchable.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private static void requireSelectedScope(String scope, long branch) {
        boolean otherBranch = scope != null && !"all".equals(scope) && !Long.toString(branch).equals(scope);

        if (otherBranch) {
            throw notFound();
        }
    }

    private static void requireValidKey(String key) {
        boolean valid = key != null
                && key.length() >= MIN_KEY_LENGTH
                && key.length() <= MAX_KEY_LENGTH
                && key.chars().allMatch(c -> c >= MIN_KEY_CHAR && c <= MAX_KEY_CHAR);

        if (!valid) {
            throw ApiException.validation("Idempotency-Key", "16~128자의 멱등 키가 필요합니다.");
        }
    }

    private static String toReloadPath(long id) {
        return "/api/invitations/" + id;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static ApiException duplicateInvitation() {
        return ApiException.conflict("DUPLICATE_INVITATION", "같은 이메일과 지점에 진행 중인 초대가 있습니다.");
    }

    private static ApiException invalidState() {
        return ApiException.conflict("INVALID_STATE", "현재 초대 상태에서는 처리할 수 없습니다.");
    }

    private record EmailAddress(@Email String value) {
    }

    @FunctionalInterface
    private interface Change {

        Invitation apply(AuthorizationState state, Stored before, Optional<InvitationTarget> target, Instant now);
    }
}
