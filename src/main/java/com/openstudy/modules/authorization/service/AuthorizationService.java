package com.openstudy.modules.authorization.service;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.repository.AuthorizationRepository;

@Service
public class AuthorizationService {
    private final AuthorizationRepository repository;
    public AuthorizationService(AuthorizationRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public AuthorizationState current(long employeeId) {
        var state = repository.current(employeeId);
        if (!"ACTIVE".equals(state.status())) throw forbidden();
        return state;
    }

    public boolean permits(AuthorizationState state, long branchId, BranchAction action) {
        if (!"ACTIVE".equals(state.status()) || !state.branches().containsKey(branchId)) return false;
        if (action.requiresOperatingBranch && !"ACTIVE".equals(state.branches().get(branchId))) return false;
        if (state.owner()) return true;
        var membership = state.memberships().get(branchId);
        return membership != null && "ACTIVE".equals(membership.status())
                && (!action.administrator || "MANAGER".equals(membership.role()))
                && (action.grant == null || membership.grants().contains(action.grant));
    }

    @Transactional(readOnly = true)
    public Set<Long> scope(long employeeId, String scope, BranchAction action) {
        return scope(current(employeeId), scope, action);
    }

    public Set<Long> scope(AuthorizationState state, String scope, BranchAction action) {
        if (scope == null || "all".equals(scope)) {
            Set<Long> result = new LinkedHashSet<>();
            state.branches().keySet().stream().sorted().filter(id -> permits(state, id, action)).forEach(result::add);
            return Set.copyOf(result);
        }
        if (!scope.matches("[1-9][0-9]*")) throw ApiException.validation("scope", "올바른 지점 범위를 입력해 주세요.");
        try {
            long branch = Long.parseLong(scope);
            require(state, Set.of(branch), action);
            return Set.of(branch);
        } catch (NumberFormatException exception) {
            throw ApiException.validation("scope", "올바른 지점 범위를 입력해 주세요.");
        }
    }

    public void require(AuthorizationState state, Set<Long> serverBranchIds, BranchAction action) {
        if (serverBranchIds.isEmpty() || serverBranchIds.stream().anyMatch(id -> !permits(state, id, action))) throw forbidden();
    }

    /** IDs must come from the persisted product AND requested new scope, not a client canEdit flag. */
    @Transactional(readOnly = true)
    public void requireSharedProduct(long actor, Set<Long> persisted, Set<Long> requested, boolean administration) {
        requireSharedProduct(current(actor), persisted, requested, administration);
    }

    /** For guarded writes use the current state supplied by AuthorizedCommands, never a prior snapshot. */
    public void requireSharedProduct(AuthorizationState actor, Set<Long> persisted, Set<Long> requested, boolean administration) {
        Set<Long> branches = new LinkedHashSet<>(persisted);
        branches.addAll(requested);
        require(actor, branches, administration ? BranchAction.MANAGE_PRODUCT : BranchAction.EDIT_PRODUCT);
    }

    @Transactional(readOnly = true)
    public Set<Long> visibleProductBranches(long actor, Set<Long> persisted) {
        Set<Long> visible = new LinkedHashSet<>(scope(current(actor), "all", BranchAction.READ));
        visible.retainAll(persisted);
        return Set.copyOf(visible);
    }

    /** Used for create/reissue/cancel/approve/reject after persisted invitation/target roles are loaded. */
    public void requireInvitationCommand(AuthorizationState actor, long branch, String invitationRole, String currentTargetRole) {
        require(actor, Set.of(branch), BranchAction.MANAGE_PEOPLE);
        if (!Set.of("STAFF", "MANAGER").contains(invitationRole)) throw ApiException.validation("role", "올바른 역할을 입력해 주세요.");
        if (!actor.owner() && (!"STAFF".equals(invitationRole)
                || (currentTargetRole != null && !"STAFF".equals(currentTargetRole)))) throw forbidden();
    }

    public void requireMembershipChange(AuthorizationState actor, AuthorizationState target, long branch,
            String requestedRole, String requestedStatus) {
        if (actor.employeeId() == target.employeeId() || target.owner()) throw forbidden();
        if (!Set.of("MANAGER", "STAFF").contains(requestedRole)
                || !Set.of("ACTIVE", "REVOKED").contains(requestedStatus)) throw ApiException.validation("role", "허용되지 않은 소속 변경입니다.");
        var existing = target.memberships().get(branch);
        if (existing == null || !"ACTIVE".equals(existing.status())) {
            throw ApiException.conflict("INVALID_STATE", "소속 복구는 재초대와 승인이 필요합니다.");
        }
        require(actor, Set.of(branch), BranchAction.MANAGE_PEOPLE);
        if (!actor.owner() && (!"STAFF".equals(existing.role()) || !"STAFF".equals(requestedRole))) throw forbidden();
    }

    public void requireOwner(AuthorizationState actor) { if (!actor.owner() || !"ACTIVE".equals(actor.status())) throw forbidden(); }
    public static AccessDeniedException forbidden() { return new AccessDeniedException("접근 권한이 없습니다."); }
}
