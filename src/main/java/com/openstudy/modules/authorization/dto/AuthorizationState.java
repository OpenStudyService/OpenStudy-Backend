package com.openstudy.modules.authorization.dto;

import java.util.Map;
import java.util.Set;

/** Current database projection. No credentials or session authorities enter policy decisions. */
public record AuthorizationState(long employeeId, boolean owner, String status, Map<Long, Membership> memberships,
        Map<Long, String> branches) {
    public AuthorizationState {
        memberships = Map.copyOf(memberships);
        branches = Map.copyOf(branches);
    }
    public record Membership(String role, String status, Set<String> grants) {
        public Membership { grants = Set.copyOf(grants); }
    }
}
