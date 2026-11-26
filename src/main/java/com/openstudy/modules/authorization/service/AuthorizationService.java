package com.openstudy.modules.authorization.service;

import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.repository.AuthorizationRepository;

@Service
@RequiredArgsConstructor
public class AuthorizationService {

    private static final String ACTIVE = "ACTIVE";
    private static final String MANAGER = "MANAGER";
    private static final String STAFF = "STAFF";
    private static final String ALL_SCOPE = "all";
    private static final String BRANCH_SCOPE_FORMAT = "[1-9][0-9]*";
    private static final Set<String> BRANCH_ROLES = Set.of(STAFF, MANAGER);
    private static final Set<String> MEMBERSHIP_CHANGE_STATUSES = Set.of(ACTIVE, "REVOKED");

    private final AuthorizationRepository authorizationRepository;

    @Transactional(readOnly = true)
    public AuthorizationState current(long employeeId) {
        AuthorizationState state = authorizationRepository.current(employeeId);

        if (!ACTIVE.equals(state.status())) {
            throw forbidden();
        }

        return state;
    }

    /**
     * For a record that names a client-supplied branch after the request was refused:
     * audit_events.branch_id is a foreign key.
     */
    @Transactional(readOnly = true)
    public boolean branchExists(long branchId) {
        return authorizationRepository.findBranches()
                .stream()
                .anyMatch(row -> ((Number) row[0]).longValue() == branchId);
    }

    public boolean permits(AuthorizationState state, long branchId, BranchAction action) {
        boolean activeInKnownBranch = ACTIVE.equals(state.status()) && state.branches().containsKey(branchId);

        if (!activeInKnownBranch) {
            return false;
        }

        boolean branchStopped = !ACTIVE.equals(state.branches().get(branchId));

        if (action.requiresOperatingBranch && branchStopped) {
            return false;
        }

        if (state.owner()) {
            return true;
        }

        AuthorizationState.Membership membership = state.memberships().get(branchId);
        boolean activeMember = membership != null && ACTIVE.equals(membership.status());

        return activeMember
                && (!action.administrator || MANAGER.equals(membership.role()))
                && (action.grant == null || membership.grants().contains(action.grant));
    }

    @Transactional(readOnly = true)
    public Set<Long> scope(long employeeId, String scope, BranchAction action) {
        return scope(current(employeeId), scope, action);
    }

    public Set<Long> scope(AuthorizationState state, String scope, BranchAction action) {
        if (scope == null || ALL_SCOPE.equals(scope)) {
            Set<Long> result = new LinkedHashSet<>();
            state.branches()
                    .keySet()
                    .stream()
                    .sorted()
                    .filter(id -> permits(state, id, action))
                    .forEach(result::add);

            return Set.copyOf(result);
        }

        if (!scope.matches(BRANCH_SCOPE_FORMAT)) {
            throw invalidScope();
        }

        try {
            long branch = Long.parseLong(scope);
            require(state, Set.of(branch), action);

            return Set.of(branch);
        } catch (NumberFormatException exception) {
            throw invalidScope();
        }
    }

    public void require(AuthorizationState state, Set<Long> serverBranchIds, BranchAction action) {
        boolean denied = serverBranchIds.isEmpty()
                || serverBranchIds.stream().anyMatch(id -> !permits(state, id, action));

        if (denied) {
            throw forbidden();
        }
    }

    /** At least one branch must permit the action; OWNER passes even when the set is empty (an unassigned customer). */
    public void requireAny(AuthorizationState state, Set<Long> serverBranchIds, BranchAction action) {
        if (!permitsAny(state, serverBranchIds, action)) {
            throw forbidden();
        }
    }

    public boolean permitsAny(AuthorizationState state, Set<Long> serverBranchIds, BranchAction action) {
        if (!ACTIVE.equals(state.status())) {
            return false;
        }

        return state.owner() || serverBranchIds.stream().anyMatch(id -> permits(state, id, action));
    }

    /** IDs must come from the persisted product AND requested new scope, not a client canEdit flag. */
    @Transactional(readOnly = true)
    public void requireSharedProduct(long actor, Set<Long> persisted, Set<Long> requested, boolean administration) {
        requireSharedProduct(current(actor), persisted, requested, administration);
    }

    /** For guarded writes use the current state supplied by AuthorizedCommands, never a prior snapshot. */
    public void requireSharedProduct(
            AuthorizationState actor,
            Set<Long> persisted,
            Set<Long> requested,
            boolean administration
    ) {
        Set<Long> branches = new LinkedHashSet<>(persisted);
        branches.addAll(requested);
        BranchAction action = administration ? BranchAction.MANAGE_PRODUCT : BranchAction.EDIT_PRODUCT;

        require(actor, branches, action);
    }

    @Transactional(readOnly = true)
    public Set<Long> visibleProductBranches(long actor, Set<Long> persisted) {
        Set<Long> visible = new LinkedHashSet<>(scope(current(actor), ALL_SCOPE, BranchAction.READ));
        visible.retainAll(persisted);

        return Set.copyOf(visible);
    }

    /** Used for create/reissue/cancel/approve/reject after persisted invitation/target roles are loaded. */
    public void requireInvitationCommand(
            AuthorizationState actor,
            long branch,
            String invitationRole,
            String currentTargetRole
    ) {
        require(actor, Set.of(branch), BranchAction.MANAGE_PEOPLE);

        if (!BRANCH_ROLES.contains(invitationRole)) {
            throw ApiException.validation("role", "올바른 역할을 입력해 주세요.");
        }

        boolean targetIsStaff = currentTargetRole == null || STAFF.equals(currentTargetRole);
        boolean staffOnly = STAFF.equals(invitationRole) && targetIsStaff;

        if (!actor.owner() && !staffOnly) {
            throw forbidden();
        }
    }

    public void requireMembershipChange(
            AuthorizationState actor,
            AuthorizationState target,
            long branch,
            String requestedRole,
            String requestedStatus
    ) {
        if (actor.employeeId() == target.employeeId() || target.owner()) {
            throw forbidden();
        }

        boolean allowedChange = BRANCH_ROLES.contains(requestedRole)
                && MEMBERSHIP_CHANGE_STATUSES.contains(requestedStatus);

        if (!allowedChange) {
            throw ApiException.validation("role", "허용되지 않은 소속 변경입니다.");
        }

        AuthorizationState.Membership existing = target.memberships().get(branch);

        if (existing == null || !ACTIVE.equals(existing.status())) {
            throw ApiException.conflict("INVALID_STATE", "소속 복구는 재초대와 승인이 필요합니다.");
        }

        require(actor, Set.of(branch), BranchAction.MANAGE_PEOPLE);
        boolean staffOnly = STAFF.equals(existing.role()) && STAFF.equals(requestedRole);

        if (!actor.owner() && !staffOnly) {
            throw forbidden();
        }
    }

    public void requireOwner(AuthorizationState actor) {
        if (!actor.owner() || !ACTIVE.equals(actor.status())) {
            throw forbidden();
        }
    }

    public static AccessDeniedException forbidden() {
        return new AccessDeniedException("접근 권한이 없습니다.");
    }

    private static ApiException invalidScope() {
        return ApiException.validation("scope", "올바른 지점 범위를 입력해 주세요.");
    }
}
