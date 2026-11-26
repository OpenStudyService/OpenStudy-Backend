package com.openstudy.modules.authorization.service;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
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
@RequiredArgsConstructor
public class AuthorizedCommands {

    private static final String ACTIVE = "ACTIVE";

    private final AuthorizationRepository authorizationRepository;
    private final AuthorizationService authorizationService;

    @Transactional
    public <T> T ownerCommand(long actor, Supplier<T> command) {
        lockFoundation();
        authorizationService.requireOwner(authorizationRepository.currentLocked(actor));

        return command.get();
    }

    @Transactional
    public <T> T inBranches(long actor, Set<Long> serverBranchIds, BranchAction action, Supplier<T> command) {
        return inBranches(actor, serverBranchIds, action, () -> {}, command);
    }

    /** Lock an existing product/invitation parent before the membership/grant current read. */
    @Transactional
    public <T> T inBranches(
            long actor,
            Set<Long> serverBranchIds,
            BranchAction action,
            Runnable lockBusinessParent,
            Supplier<T> command
    ) {
        return inBranches(actor, serverBranchIds, action, lockBusinessParent, state -> command.get());
    }

    @Transactional
    public <T> T inBranches(
            long actor,
            Set<Long> serverBranchIds,
            BranchAction action,
            Runnable lockBusinessParent,
            Function<AuthorizationState, T> command
    ) {
        lockFoundation();
        lockBusinessParent.run();
        AuthorizationState state = authorizationRepository.currentLocked(actor);
        authorizationService.require(state, serverBranchIds, action);

        return command.apply(state);
    }

    /**
     * For a record shared by several branches where any one of them may act, e.g. a customer who registered at one
     * branch and paid at another (M4-D05). The branch set is read after the parent lock, so it is the persisted one.
     */
    @Transactional
    public <T> T inAnyBranch(
            long actor,
            BranchAction action,
            Runnable lockBusinessParent,
            Supplier<Set<Long>> serverBranchIds,
            Function<AuthorizationState, T> command
    ) {
        lockFoundation();
        lockBusinessParent.run();
        AuthorizationState state = authorizationRepository.currentLocked(actor);
        authorizationService.requireAny(state, serverBranchIds.get(), action);

        return command.apply(state);
    }

    /**
     * For a record that looks nonexistent outside its own branch, e.g. a seat or a seat session: missing and out of
     * reach are the same 404, decided under the guard rather than from a snapshot read before it. The record's branch
     * must be immutable; the callback receives it and takes the row lock itself.
     */
    @Transactional
    public <T> T inRecordBranch(
            long actor,
            BranchAction action,
            Supplier<Optional<Long>> recordBranch,
            BiFunction<AuthorizationState, Long, T> command
    ) {
        lockFoundation();
        AuthorizationState state = requireActive(authorizationRepository.currentLocked(actor));
        long branch = recordBranch.get()
                .filter(id -> authorizationService.permits(state, id, action))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        return command.apply(state, branch);
    }

    /**
     * For a record whose own rule decides between 403 and 404, e.g. a channel that only its participants and managers
     * may know about: there is no branch to check up front, so the callback rules on the locked parent with the state
     * read under the guard. Only an account that is not active is refused here.
     */
    @Transactional
    public <T> T activeEmployee(long actor, Runnable lockBusinessParent, Function<AuthorizationState, T> command) {
        lockFoundation();
        lockBusinessParent.run();
        AuthorizationState state = requireActive(authorizationRepository.currentLocked(actor));

        return command.apply(state);
    }

    /**
     * Same lock order without an authenticated actor, for a credential-bearing public command such as
     * invitation acceptance. The callback must verify its own credential under the parent lock.
     */
    @Transactional
    public <T> T publicCommand(Runnable lockBusinessParent, Supplier<T> command) {
        lockFoundation();
        lockBusinessParent.run();

        return command.get();
    }

    @Transactional
    public <T> T membershipChange(
            long actor,
            long target,
            long branch,
            String requestedRole,
            String requestedStatus,
            Supplier<T> command
    ) {
        lockFoundation();
        authorizationRepository.lockMemberships(toIdSet(actor, target), Set.of(branch));
        authorizationService.requireMembershipChange(
                authorizationRepository.currentLocked(actor),
                authorizationRepository.currentLocked(target),
                branch,
                requestedRole,
                requestedStatus
        );
        long administrators = authorizationRepository.activeAdministrators(branch);

        T result = command.get();

        boolean lostLastAdministrator = administrators > 0 && authorizationRepository.activeAdministrators(branch) == 0;

        if (lostLastAdministrator) {
            throw lastAdministrator();
        }

        return result;
    }

    @Transactional
    public <T> T selfAccountChange(long actor, Supplier<T> command) {
        lockFoundation();
        requireActive(authorizationRepository.currentLocked(actor));

        return command.get();
    }

    @Transactional
    public <T> T accountChange(long actor, long target, Supplier<T> command) {
        // Lock branches before the full membership projection to preserve parent/child lock order.
        lockFoundation();
        authorizationService.requireOwner(authorizationRepository.currentLocked(actor));

        AuthorizationState targetState = authorizationRepository.currentLocked(target);
        Set<Long> branches = targetState.owner()
                ? targetState.branches().keySet()
                : targetState.memberships().keySet();
        authorizationRepository.lockMemberships(toIdSet(actor, target), branches);

        long owners = authorizationRepository.activeOwners();
        Set<Long> protectedBranches = new HashSet<>();

        for (long branch : branches) {
            if (authorizationRepository.activeAdministrators(branch) > 0) {
                protectedBranches.add(branch);
            }
        }

        T result = command.get();

        if (owners > 0 && authorizationRepository.activeOwners() == 0) {
            throw lastAdministrator();
        }

        for (long branch : protectedBranches) {
            if (authorizationRepository.activeAdministrators(branch) == 0) {
                throw lastAdministrator();
            }
        }

        return result;
    }

    /** The common lock order's first three steps: guard, sorted employees, sorted branches. */
    private void lockFoundation() {
        authorizationRepository.lockGuard();
        authorizationRepository.lockEmployees();
        authorizationRepository.lockBranches();
    }

    private static AuthorizationState requireActive(AuthorizationState state) {
        if (!ACTIVE.equals(state.status())) {
            throw AuthorizationService.forbidden();
        }

        return state;
    }

    private static Set<Long> toIdSet(long actor, long target) {
        Set<Long> result = new HashSet<>();
        result.add(actor);
        result.add(target);

        return result;
    }

    private static ApiException lastAdministrator() {
        return ApiException.conflict("LAST_ADMIN_REQUIRED", "마지막 활성 관리자는 해제할 수 없습니다.");
    }
}
