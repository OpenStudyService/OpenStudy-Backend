package com.openstudy.modules.workforce.service;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.workforce.entity.ShiftStatus;
import com.openstudy.modules.workforce.repository.ShiftSearchRepository;

/**
 * M8-D02 in one place, for shifts and attendance alike: a record is read by its recorded branch, which must be readable,
 * and then either managed by the reader (OWNER or that branch's MANAGER) or the reader's own. Lists, details, the weekly
 * schedule and conflict answers all ask here, so none of them can open more than another.
 */
@Component
@RequiredArgsConstructor
class RecordScope {
    private final AuthorizationService policy;

    /** OWNER or the MANAGER of that branch: the role alone, also at a stopped branch (M8-D01·D04). */
    boolean manages(AuthorizationState state, long branch) {
        return policy.permits(state, branch, BranchAction.MANAGE_SHIFTS);
    }

    /** For one record: its branch is readable, and the reader manages that branch or the record is the reader's own. */
    boolean sees(AuthorizationState state, Set<Long> readable, long branch, long employee) {
        return readable.contains(branch) && (manages(state, branch) || employee == state.employeeId());
    }

    /** Every branch the reader may read now; a branch whose membership ended is not among them. */
    Set<Long> readable(AuthorizationState state) {
        return policy.scope(state, "all", BranchAction.READ);
    }

    /**
     * The reader condition of every list: the scope's readable branches, the managed part of them, and the reader. mine
     * and an employee filter only narrow it, so a staff member naming a colleague gets nothing rather than a 403.
     */
    ShiftSearchRepository.Filter filter(AuthorizationState state, String scope, Long employee, boolean own, ShiftStatus status,
            Instant from, Instant to) {
        Set<Long> readable = policy.scope(state, scope, BranchAction.READ);
        Set<Long> managed = readable.stream().filter(branch -> manages(state, branch)).collect(Collectors.toSet());
        Long person = employee;
        if (own) {
            if (employee != null && employee != state.employeeId()) readable = Set.of();
            person = state.employeeId();
        }
        return new ShiftSearchRepository.Filter(readable, managed, state.employeeId(), person, status, from, to);
    }
}
