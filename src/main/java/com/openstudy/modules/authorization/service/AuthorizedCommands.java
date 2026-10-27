package com.openstudy.modules.authorization.service;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.repository.AuthorizationRepository;

/**
 * Transaction boundary for business services. Callbacks execute synchronously on the same transaction;
 * no HTTP/SMTP, REQUIRES_NEW, async work or pre-authorized client flags are permitted inside callbacks.
 * Lock order: guard, sorted employees, sorted branches, business parent (callback), children.
 * Current implementation serializes commands using one guard; split only after contention measurements.
 */
@Service
public class AuthorizedCommands {
    private final AuthorizationRepository repository;
    private final AuthorizationService authorization;
    public AuthorizedCommands(AuthorizationRepository repository, AuthorizationService authorization) {
        this.repository = repository;
        this.authorization = authorization;
    }

    @Transactional
    public <T> T ownerCommand(long actor, Supplier<T> command) {
        repository.lockGuard();
        repository.lockEmployees();
        repository.lockBranches();
        authorization.requireOwner(repository.currentLocked(actor));
        return command.get();
    }

    @Transactional
    public <T> T inBranches(long actor, Set<Long> serverBranchIds, BranchAction action, Supplier<T> command) {
        return inBranches(actor, serverBranchIds, action, () -> {}, command);
    }

    /** Lock an existing product/invitation parent before the membership/grant current read. */
    @Transactional
    public <T> T inBranches(long actor, Set<Long> serverBranchIds, BranchAction action, Runnable lockBusinessParent,
            Supplier<T> command) {
        return inBranches(actor, serverBranchIds, action, lockBusinessParent, state -> command.get());
    }

    @Transactional
    public <T> T inBranches(long actor, Set<Long> serverBranchIds, BranchAction action, Runnable lockBusinessParent,
            Function<AuthorizationState, T> command) {
        repository.lockGuard();
        repository.lockEmployees();
        repository.lockBranches();
        lockBusinessParent.run();
        var state = repository.currentLocked(actor);
        authorization.require(state, serverBranchIds, action);
        return command.apply(state);
    }

    /**
     * Same lock order without an authenticated actor, for a credential-bearing public command such as
     * invitation acceptance. The callback must verify its own credential under the parent lock.
     */
    @Transactional
    public <T> T publicCommand(Runnable lockBusinessParent, Supplier<T> command) {
        repository.lockGuard();
        repository.lockEmployees();
        repository.lockBranches();
        lockBusinessParent.run();
        return command.get();
    }

    @Transactional
    public <T> T membershipChange(long actor, long target, long branch, String requestedRole, String requestedStatus,
            Supplier<T> command) {
        repository.lockGuard();
        repository.lockEmployees();
        repository.lockBranches();
        repository.lockMemberships(ids(actor, target), Set.of(branch));
        authorization.requireMembershipChange(repository.currentLocked(actor), repository.currentLocked(target), branch,
                requestedRole, requestedStatus);
        long administrators = repository.activeAdministrators(branch);
        T result = command.get();
        if (administrators > 0 && repository.activeAdministrators(branch) == 0) throw lastAdministrator();
        return result;
    }

    @Transactional
    public <T> T selfAccountChange(long actor, Supplier<T> command) {
        repository.lockGuard();
        repository.lockEmployees();
        repository.lockBranches();
        if (!"ACTIVE".equals(repository.currentLocked(actor).status())) throw AuthorizationService.forbidden();
        return command.get();
    }

    @Transactional
    public <T> T accountChange(long actor, long target, Supplier<T> command) {
        repository.lockGuard();
        repository.lockEmployees();
        // Lock branches before the full membership projection to preserve parent/child lock order.
        repository.lockBranches();
        authorization.requireOwner(repository.currentLocked(actor));
        var targetState = repository.currentLocked(target);
        Set<Long> branches = targetState.owner() ? targetState.branches().keySet() : targetState.memberships().keySet();
        repository.lockMemberships(ids(actor, target), branches);
        long owners = repository.activeOwners();
        Set<Long> protectedBranches = new HashSet<>();
        for (long branch : branches) if (repository.activeAdministrators(branch) > 0) protectedBranches.add(branch);
        T result = command.get();
        if (owners > 0 && repository.activeOwners() == 0) throw lastAdministrator();
        for (long branch : protectedBranches) if (repository.activeAdministrators(branch) == 0) throw lastAdministrator();
        return result;
    }

    private static Set<Long> ids(long actor, long target) {
        Set<Long> result = new HashSet<>(); result.add(actor); result.add(target); return result;
    }
    private static ApiException lastAdministrator() {
        return ApiException.conflict("LAST_ADMIN_REQUIRED", "마지막 활성 관리자는 해제할 수 없습니다.");
    }
}
