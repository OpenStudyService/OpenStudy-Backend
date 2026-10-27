package com.openstudy.modules.authorization.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.modules.authorization.dto.MembershipRecord;
import com.openstudy.modules.authorization.entity.BranchMembership;
import com.openstudy.modules.authorization.entity.BranchRole;
import com.openstudy.modules.authorization.entity.MembershipStatus;
import com.openstudy.modules.authorization.repository.BranchMembershipRepository;
import com.openstudy.modules.authorization.repository.PermissionDefinitionRepository;

/**
 * Branch memberships and their grants (JPA-03). Every write runs inside the caller's command transaction, which
 * already holds the AuthorizedCommands lock order; each command advances a membership's version exactly once,
 * like the former {@code version=version+1}. Unknown permission codes are skipped, as the former INSERT ... SELECT did.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class MembershipService {
    private final BranchMembershipRepository memberships;
    private final PermissionDefinitionRepository definitions;
    private final EntityManager entityManager;

    public MembershipService(BranchMembershipRepository memberships, PermissionDefinitionRepository definitions,
            EntityManager entityManager) {
        this.memberships = memberships;
        this.definitions = definitions;
        this.entityManager = entityManager;
    }

    /** Active memberships with their grants, for the current-user response. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<MembershipRecord> active(long employeeId) {
        return memberships.findByEmployeeIdAndStatusOrderByBranchIdAsc(employeeId, MembershipStatus.ACTIVE).stream()
                .map(m -> record(m, m.getPermissionCodes())).toList();
    }

    /** Every membership of the employee. Locked: membership rows FOR UPDATE, then their grant rows FOR SHARE. */
    @Transactional(propagation = Propagation.SUPPORTS)
    public List<MembershipRecord> all(long employeeId, boolean lock) {
        if (!lock) return memberships.findByEmployeeIdOrderByBranchIdAsc(employeeId).stream()
                .map(m -> record(m, m.getPermissionCodes())).toList();
        return memberships.findForUpdateByEmployeeIdOrderByBranchIdAsc(employeeId).stream()
                .map(m -> record(m, memberships.lockedPermissionCodes(m.getId()))).toList();
    }

    /** Locks the (employee, branch) membership row, or its gap when there is none; grants are not read. */
    public Optional<MembershipRecord> lock(long employeeId, long branchId) {
        return memberships.findForUpdateByEmployeeIdAndBranchId(employeeId, branchId).map(m -> record(m, List.of()));
    }

    /** Returns whether a PENDING membership was revoked. */
    public boolean revokePending(long employeeId, long branchId, long actor) {
        var membership = memberships.findForUpdateByEmployeeIdAndBranchId(employeeId, branchId)
                .filter(m -> m.getStatus() == MembershipStatus.PENDING);
        if (membership.isEmpty()) return false;
        membership.get().revoke(actor);
        memberships.flush();
        return true;
    }

    /** Reuses a revoked row (Q11) or inserts one. A pending membership never carries effective grants. */
    public void pend(long employeeId, long branchId, String role, long actor) {
        var existing = memberships.findForUpdateByEmployeeIdAndBranchId(employeeId, branchId);
        if (existing.isEmpty()) {
            memberships.saveAndFlush(BranchMembership.pending(employeeId, branchId, BranchRole.valueOf(role), actor));
            return;
        }
        existing.get().returnToPending(BranchRole.valueOf(role), actor);
        memberships.flush();
    }

    /** Activates only the invitation's PENDING membership and replaces its grants. */
    public void activatePending(long employeeId, long branchId, long actor, List<String> grants) {
        var membership = memberships.findForUpdateByEmployeeIdAndBranchIdAndStatus(employeeId, branchId, MembershipStatus.PENDING)
                .orElseThrow(() -> new IllegalStateException("No pending membership to activate"));
        memberships.activate(membership.getId(), actor);
        entityManager.refresh(membership);
        membership.replacePermissions(new LinkedHashSet<>(definitions.findByCodeIn(grants)));
        memberships.flush();
    }

    /** The caller has locked this membership through {@link #all(long, boolean)} and checked its version. */
    public void update(long employeeId, long branchId, long actor, String role, String status, List<String> grants) {
        var membership = memberships.findForUpdateByEmployeeIdAndBranchId(employeeId, branchId).orElseThrow();
        long version = membership.getVersion();
        membership.change(BranchRole.valueOf(role), MembershipStatus.valueOf(status), actor);
        membership.replacePermissions(new LinkedHashSet<>(definitions.findByCodeIn(grants)));
        memberships.flush();
        if (membership.getVersion() == version) memberships.incrementVersion(membership.getId());
        entityManager.refresh(membership);
    }

    private static MembershipRecord record(BranchMembership m, List<String> permissions) {
        return new MembershipRecord(m.getId(), m.getBranchId(), m.getRole().name(), m.getStatus().name(), permissions, m.getVersion());
    }
}
