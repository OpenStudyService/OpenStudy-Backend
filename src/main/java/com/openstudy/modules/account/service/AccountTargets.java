package com.openstudy.modules.account.service;

import java.util.List;
import java.util.function.LongPredicate;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.openstudy.common.exception.ApiException;
import com.openstudy.modules.account.repository.AccountCredentialRepository.Account;
import com.openstudy.modules.account.repository.AccountCredentialRepository.Membership;
import com.openstudy.modules.authorization.service.AuthorizationService;

/**
 * Who may reset or unlock whose account (M14-D05, account-collab-api.md "권한과 대상"), checked in the contract order:
 * not visible → 404, self (reset only) → 403 SELF_RESET_FORBIDDEN, an OWNER by a non-owner → 403, no active membership
 * the actor manages → 403. Visibility is that of GET /employees/{id}: an owner sees everyone, a manager sees whoever has a
 * membership, in any status, in a branch where the manager holds people:manage.
 */
final class AccountTargets {
    private AccountTargets() {}

    /**
     * @param managesPeople whether the actor holds people:manage in a branch (always true for an owner)
     */
    static void requireManageable(long actor, boolean actorOwner, Account target, List<Membership> memberships,
            LongPredicate managesPeople, boolean selfAllowed) {
        boolean visible = actorOwner || memberships.stream().anyMatch(m -> managesPeople.test(m.branchId()));
        if (!visible) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!selfAllowed && target.id() == actor)
            throw ApiException.forbidden("SELF_RESET_FORBIDDEN", "본인 비밀번호는 비밀번호 변경에서 바꿀 수 있습니다.");
        if (actorOwner) return;
        if (target.owner()) throw AuthorizationService.forbidden();
        boolean manages = memberships.stream().anyMatch(m -> "ACTIVE".equals(m.status()) && managesPeople.test(m.branchId()));
        if (!manages) throw AuthorizationService.forbidden();
    }

}
