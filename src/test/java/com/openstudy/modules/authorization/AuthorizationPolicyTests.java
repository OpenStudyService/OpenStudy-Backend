package com.openstudy.modules.authorization;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import static org.assertj.core.api.Assertions.*;

class AuthorizationPolicyTests {
    private final AuthorizationService policy = new AuthorizationService(null);
    private AuthorizationState state(long id, boolean owner, String role, Set<String> grants) {
        return new AuthorizationState(id, owner, "ACTIVE", Map.of(1L,
                new AuthorizationState.Membership(role, "ACTIVE", grants)), Map.of(1L, "ACTIVE", 2L, "SUSPENDED"));
    }
    @Test
    void roleAndExplicitGrantMatrixAndScopeUnion() {
        var owner = state(1, true, "STAFF", Set.of());
        var manager = state(2, false, "MANAGER", Set.of("people:manage", "operations:write"));
        var staff = state(3, false, "STAFF", Set.of("people:manage", "operations:write"));
        for (var action : BranchAction.values()) assertThat(policy.permits(owner, 1, action)).isTrue();
        assertThat(policy.permits(manager, 1, BranchAction.MANAGE_PEOPLE)).isTrue();
        assertThat(policy.permits(state(2, false, "MANAGER", Set.of()), 1, BranchAction.MANAGE_PEOPLE)).isFalse();
        assertThat(policy.permits(staff, 1, BranchAction.MANAGE_PEOPLE)).isFalse();
        assertThat(policy.permits(staff, 1, BranchAction.EDIT_PRODUCT)).isTrue();
        assertThat(policy.permits(staff, 1, BranchAction.MANAGE_PRODUCT)).isFalse();
        assertThat(policy.permits(staff, 1, BranchAction.READ_PAYMENTS)).isFalse();
        assertThat(policy.scope(staff, "all", BranchAction.READ)).containsExactly(1L);
        assertThat(policy.scope(owner, "all", BranchAction.READ)).containsExactlyInAnyOrder(1L, 2L);
        assertThat(policy.permits(owner, 2, BranchAction.READ)).isTrue();
        assertThat(policy.permits(owner, 2, BranchAction.NEW_OPERATION)).isFalse();
        assertThatThrownBy(() -> policy.scope(staff, "2", BranchAction.READ)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        for (String malformed : new String[] {"0", "-1", "1,2", "all OR 1=1", "9999999999999999999999999"})
            assertThatThrownBy(() -> policy.scope(staff, malformed, BranchAction.READ)).isInstanceOf(com.openstudy.common.exception.ApiException.class);
        assertThatThrownBy(() -> policy.require(staff, Set.of(1L, 2L), BranchAction.EDIT_PRODUCT))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test
    void delegationProtectsSelfOwnerAndBothCurrentAndRequestedRole() {
        var manager = state(2, false, "MANAGER", Set.of("people:manage"));
        var staff = state(3, false, "STAFF", Set.of());
        policy.requireMembershipChange(manager, staff, 1, "STAFF", "REVOKED");
        for (var target : new AuthorizationState[] {manager, state(4, true, "STAFF", Set.of()), state(5, false, "MANAGER", Set.of())})
            assertThatThrownBy(() -> policy.requireMembershipChange(manager, target, 1, "STAFF", "ACTIVE"))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> policy.requireMembershipChange(manager, staff, 1, "MANAGER", "ACTIVE"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        policy.requireInvitationCommand(manager, 1, "STAFF", null);
        assertThatThrownBy(() -> policy.requireInvitationCommand(manager, 1, "MANAGER", null))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> policy.requireInvitationCommand(manager, 1, "STAFF", "MANAGER"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        policy.requireMembershipChange(state(1, true, "STAFF", Set.of()), state(5, false, "MANAGER", Set.of()), 1, "STAFF", "ACTIVE");
        var revoked = new AuthorizationState(3, false, "ACTIVE", Map.of(1L,
                new AuthorizationState.Membership("STAFF", "REVOKED", Set.of())), Map.of(1L, "ACTIVE"));
        assertThatThrownBy(() -> policy.requireMembershipChange(manager, revoked, 1, "STAFF", "ACTIVE"))
                .isInstanceOf(com.openstudy.common.exception.ApiException.class);
    }
}
