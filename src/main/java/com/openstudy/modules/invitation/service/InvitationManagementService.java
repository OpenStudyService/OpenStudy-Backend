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
public class InvitationManagementService {
    private static final Duration LIFETIME = Duration.ofHours(24);
    private static final Set<String> STATUSES = Set.of("초대 대기", "승인 대기", "활성", "취소", "거절", "만료");
    private final InvitationManagementRepository repository;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final PermissionPresetService presets;
    private final EmployeeAccountService employees;
    private final Clock clock;
    private final Validator validator;

    public InvitationManagementService(InvitationManagementRepository repository, AuthorizationService policy,
            AuthorizedCommands commands, PermissionPresetService presets, EmployeeAccountService employees,
            Clock clock, Validator validator) {
        this.repository = repository;
        this.policy = policy;
        this.commands = commands;
        this.presets = presets;
        this.employees = employees;
        this.clock = clock;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public PageResponse<Invitation> list(long actor, String scope, ListQuery query, String status) {
        if (status != null && !STATUSES.contains(status)) throw ApiException.validation("status", "올바른 초대 상태를 선택해 주세요.");
        Set<Long> visible = visible(actor, scope);
        Instant now = clock.instant();
        var rows = repository.ids(visible).stream().map(id -> required(id, false, now).view())
                .filter(invitation -> status == null || status.equals(invitation.status()))
                .filter(invitation -> (invitation.name() + " " + invitation.email()).toLowerCase(Locale.ROOT)
                        .contains(query.q().toLowerCase(Locale.ROOT))).toList();
        Comparator<Invitation> comparator = switch (query.sort()) {
            case "name" -> Comparator.comparing(Invitation::name);
            case "expiresAt" -> Comparator.comparing(Invitation::expiresAt);
            default -> Comparator.comparing(Invitation::createdAt);
        };
        return PageResponse.fromAuthorized(rows, query, comparator,
                Comparator.comparingLong(invitation -> Long.parseLong(invitation.id())));
    }

    @Transactional(readOnly = true)
    public Invitation get(long actor, long id, String scope) {
        var visible = visible(actor, scope);
        if (!repository.ids(visible).contains(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return required(id, false, clock.instant()).view();
    }

    public Invitation create(long actor, String scope, Map<String, Object> body, String key, String requestId) {
        var input = new StrictInput(body).only("branchId", "name", "email", "role", "presetId", "permissions");
        long branch = input.id("branchId");
        String name = input.text("name", 100);
        String email = input.text("email", 254).toLowerCase(Locale.ROOT);
        if (!validator.validate(new EmailAddress(email)).isEmpty()) throw ApiException.validation("email", "올바른 이메일을 입력해 주세요.");
        String role = input.choice("role", "admin", "staff");
        String databaseRole = "admin".equals(role) ? "MANAGER" : "STAFF";
        List<String> permissions = input.permissions();
        Long preset = input.has("presetId") ? input.id("presetId") : null;
        validKey(key);
        selectedScope(scope, branch);
        return commands.inBranches(actor, Set.of(branch), BranchAction.MANAGE_PEOPLE,
                () -> repository.lockCandidates(branch, email), state -> {
            Instant now = clock.instant();
            var target = employees.invitationTarget(email, branch);
            requireTarget(state, branch, databaseRole, target);
            // Check current actor and current target role before replaying a previously authorized result.
            var fingerprint = Arrays.asList(branch, name, email, role, preset, permissions);
            var replay = repository.replay(actor, branch, key, fingerprint, now);
            if (replay.isPresent()) return replay.get();
            presets.validateCopy(role, permissions, preset);
            noExistingMembership(target);
            expireAndCheckDuplicates(actor, branch, email, null, now, requestId);
            try {
                long id = repository.create(actor, branch, name, email, databaseRole, preset, permissions, now.plus(LIFETIME));
                var result = required(id, true, now).view();
                repository.changed(actor, branch, id, "CREATED", null, result, requestId);
                repository.remember(actor, branch, key, fingerprint, result, now.plus(LIFETIME));
                return result;
            } catch (DuplicateKeyException exception) {
                throw ApiException.conflict("DUPLICATE_INVITATION", "같은 이메일과 지점에 진행 중인 초대가 있습니다.");
            }
        });
    }

    public Invitation reissue(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("version");
        long version = input.version("version");
        return change(actor, id, scope, (state, before, target, now) -> {
            ApiVersion.requireCurrent(version, before.version(), reload(id));
            if (!Set.of("PENDING", "EXPIRED").contains(before.state())) throw invalidState();
            noExistingMembership(target);
            expireAndCheckDuplicates(actor, before.branch(), before.email(), id, now, requestId);
            repository.reissue(actor, before, now.plus(LIFETIME));
            var result = required(id, true, now).view();
            repository.changed(actor, before.branch(), id, "REISSUED", before.view(), result, requestId);
            return result;
        });
    }

    public Invitation cancel(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("version");
        long version = input.version("version");
        return change(actor, id, scope, (state, before, target, now) -> {
            if ("CANCELLED".equals(before.state())) return before.view();
            ApiVersion.requireCurrent(version, before.version(), reload(id));
            if (!"PENDING".equals(before.state()) || !now.isBefore(before.expiresAt())) throw invalidState();
            repository.cancel(actor, id);
            var result = required(id, true, now).view();
            repository.changed(actor, before.branch(), id, "CANCELLED", before.view(), result, requestId);
            return result;
        });
    }

    public Invitation reject(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("version", "reason");
        long version = input.version("version");
        String reason = input.text("reason", 500);
        return change(actor, id, scope, (state, before, target, now) -> {
            if ("REJECTED".equals(before.state())) return before.view();
            ApiVersion.requireCurrent(version, before.version(), reload(id));
            if (!"ACCEPTED".equals(before.state())) throw invalidState();
            if (target.isEmpty() || !Long.valueOf(target.get().employeeId()).equals(before.acceptedEmployeeId())) throw invalidState();
            if ("PENDING".equals(target.get().membershipStatus()) && !before.role().equals(target.get().membershipRole())) {
                // Acceptance owns a pending membership with the invitation's planned role. Fail closed
                // if a different workflow has left an inconsistent pending membership in this slot.
                throw invalidState();
            }
            employees.revokePendingInvitationMembership(target.get().employeeId(), before.branch(), actor, requestId);
            repository.reject(actor, id, reason);
            var result = required(id, true, now).view();
            repository.changed(actor, before.branch(), id, "REJECTED", before.view(), result, requestId);
            return result;
        });
    }

    public Invitation approve(long actor, long id, String scope, Map<String, Object> body, String requestId) {
        var input = new StrictInput(body).only("version");
        long version = input.version("version");
        return change(actor, id, scope, (state, before, target, now) -> {
            if ("APPROVED".equals(before.state())) return before.view();
            ApiVersion.requireCurrent(version, before.version(), reload(id));
            if (!"ACCEPTED".equals(before.state())) throw invalidState();
            if (target.isEmpty() || !Long.valueOf(target.get().employeeId()).equals(before.acceptedEmployeeId())) throw invalidState();
            var account = target.get();
            // Approval activates a PENDING account only. SUSPENDED/LEFT accounts are restored by the OWNER
            // account command (Q3), never as a side effect of an invitation (Q11).
            if (!Set.of("PENDING", "ACTIVE").contains(account.accountStatus())) throw invalidState();
            if (!"PENDING".equals(account.membershipStatus()) || !before.role().equals(account.membershipRole())) throw invalidState();
            // The snapshot was valid at creation; re-check it against the current code dictionary and role policy.
            presets.validateCopy(before.view().role(), before.view().permissions(), null);
            employees.activateInvitationMembership(account.employeeId(), before.branch(), actor, before.view().permissions(), requestId);
            repository.approve(actor, id);
            var result = required(id, true, now).view();
            repository.changed(actor, before.branch(), id, "APPROVED", before.view(), result, requestId);
            return result;
        });
    }

    private Invitation change(long actor, long id, String scope, Change command) {
        // Branch identity is immutable. Never trust a body/query branch for an existing invitation.
        // Apply the same visibility rule as detail before resolving an immutable parent branch.
        // A guessed ID outside the actor's scope must not become an existence oracle on writes.
        long branch = Long.parseLong(get(actor, id, scope).branchId());
        return commands.inBranches(actor, Set.of(branch), BranchAction.MANAGE_PEOPLE, () -> repository.lock(id), state -> {
            Instant now = clock.instant();
            var before = required(id, true, now);
            var target = employees.invitationTarget(before.email(), branch);
            requireTarget(state, branch, before.role(), target);
            return command.apply(state, before, target, now);
        });
    }

    private void requireTarget(AuthorizationState state, long branch, String role, Optional<InvitationTarget> target) {
        policy.requireInvitationCommand(state, branch, role, target.map(InvitationTarget::membershipRole).orElse(null));
        if (target.map(InvitationTarget::owner).orElse(false)) throw AuthorizationService.forbidden();
    }

    private Set<Long> visible(long actor, String scope) {
        var state = policy.current(actor);
        var branches = policy.scope(state, scope, BranchAction.MANAGE_PEOPLE);
        if (!state.owner() && branches.isEmpty()) throw AuthorizationService.forbidden();
        return branches;
    }

    private static void noExistingMembership(Optional<InvitationTarget> target) {
        if (target.isPresent() && "ACTIVE".equals(target.get().membershipStatus())) {
            throw ApiException.conflict("DUPLICATE_MEMBERSHIP", "이미 활성 소속이 있는 직원입니다.");
        }
    }

    private void expireAndCheckDuplicates(long actor, long branch, String email, Long except, Instant now, String requestId) {
        for (var candidate : repository.active(branch, email, now)) {
            if (except != null && candidate.id() == except) continue;
            if ("PENDING".equals(candidate.state()) && !now.isBefore(candidate.expiresAt())) {
                repository.expire(actor, candidate);
                repository.changed(actor, branch, candidate.id(), "EXPIRED", candidate.view(),
                        required(candidate.id(), true, now).view(), requestId);
            } else {
                throw ApiException.conflict("DUPLICATE_INVITATION", "같은 이메일과 지점에 진행 중인 초대가 있습니다.");
            }
        }
    }

    private Stored required(long id, boolean lock, Instant now) {
        return repository.find(id, lock, now).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static void selectedScope(String scope, long branch) {
        if (scope != null && !"all".equals(scope) && !Long.toString(branch).equals(scope)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static void validKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128 || key.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw ApiException.validation("Idempotency-Key", "16~128자의 멱등 키가 필요합니다.");
        }
    }

    private static String reload(long id) { return "/api/invitations/" + id; }
    private static ApiException invalidState() { return ApiException.conflict("INVALID_STATE", "현재 초대 상태에서는 처리할 수 없습니다."); }
    private record EmailAddress(@Email String value) {}
    @FunctionalInterface
    private interface Change {
        Invitation apply(AuthorizationState state, Stored before, Optional<InvitationTarget> target, Instant now);
    }
}
