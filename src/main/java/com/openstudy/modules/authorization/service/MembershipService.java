package com.openstudy.modules.authorization.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.modules.authorization.dto.MembershipRecord;
import com.openstudy.modules.authorization.entity.BranchMembership;
import com.openstudy.modules.authorization.entity.BranchRole;
import com.openstudy.modules.authorization.entity.MembershipStatus;
import com.openstudy.modules.authorization.entity.PermissionDefinition;
import com.openstudy.modules.authorization.repository.BranchMembershipRepository;
import com.openstudy.modules.authorization.repository.PermissionDefinitionRepository;

/**
 * Branch memberships and their grants (JPA-03). Every write runs inside the caller's command transaction, which
 * already holds the AuthorizedCommands lock order; each command advances a membership's version exactly once,
 * like the former {@code version=version+1}. Unknown permission codes are skipped, as the former INSERT ... SELECT did.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
@RequiredArgsConstructor
public class MembershipService {

    private final BranchMembershipRepository branchMembershipRepository;
    private final PermissionDefinitionRepository permissionDefinitionRepository;
    private final EntityManager entityManager;

    /** Active memberships with their grants, for the current-user response. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<MembershipRecord> active(long employeeId) {
        return branchMembershipRepository
                .findByEmployeeIdAndStatusOrderByBranchIdAsc(employeeId, MembershipStatus.ACTIVE)
                .stream()
                .map(membership -> toRecord(membership, membership.getPermissionCodes()))
                .toList();
    }

    /** Every membership of the employee. Locked: membership rows FOR UPDATE, then their grant rows FOR SHARE. */
    @Transactional(propagation = Propagation.SUPPORTS)
    public List<MembershipRecord> all(long employeeId, boolean lock) {
        if (!lock) {
            return branchMembershipRepository.findByEmployeeIdOrderByBranchIdAsc(employeeId)
                    .stream()
                    .map(membership -> toRecord(membership, membership.getPermissionCodes()))
                    .toList();
        }

        return branchMembershipRepository.findForUpdateByEmployeeIdOrderByBranchIdAsc(employeeId)
                .stream()
                .map(membership -> toRecord(
                        membership,
                        branchMembershipRepository.findPermissionCodesForShare(membership.getId())
                ))
                .toList();
    }

    /** Locks the (employee, branch) membership row, or its gap when there is none; grants are not read. */
    public Optional<MembershipRecord> lock(long employeeId, long branchId) {
        return branchMembershipRepository.findForUpdateByEmployeeIdAndBranchId(employeeId, branchId)
                .map(membership -> toRecord(membership, List.of()));
    }

    /** Returns whether a PENDING membership was revoked. */
    public boolean revokePending(long employeeId, long branchId, long actor) {
        Optional<BranchMembership> membership = branchMembershipRepository
                .findForUpdateByEmployeeIdAndBranchId(employeeId, branchId)
                .filter(found -> found.getStatus() == MembershipStatus.PENDING);

        if (membership.isEmpty()) {
            return false;
        }

        membership.get().revoke(actor);
        branchMembershipRepository.flush();
        return true;
    }

    /** Reuses a revoked row (Q11) or inserts one. A pending membership never carries effective grants. */
    public void pend(long employeeId, long branchId, String role, long actor) {
        Optional<BranchMembership> existing = branchMembershipRepository
                .findForUpdateByEmployeeIdAndBranchId(employeeId, branchId);
        BranchRole branchRole = BranchRole.valueOf(role);

        if (existing.isEmpty()) {
            branchMembershipRepository.saveAndFlush(BranchMembership.pending(employeeId, branchId, branchRole, actor));
            return;
        }

        existing.get().returnToPending(branchRole, actor);
        branchMembershipRepository.flush();
    }

    /** Activates only the invitation's PENDING membership and replaces its grants. */
    public void activatePending(long employeeId, long branchId, long actor, List<String> grants) {
        BranchMembership membership = branchMembershipRepository
                .findForUpdateByEmployeeIdAndBranchIdAndStatus(employeeId, branchId, MembershipStatus.PENDING)
                .orElseThrow(() -> new IllegalStateException("No pending membership to activate"));

        branchMembershipRepository.activate(membership.getId(), actor);
        entityManager.refresh(membership);
        membership.replacePermissions(findDefinitions(grants));
        branchMembershipRepository.flush();
    }

    /** The caller has locked this membership through {@link #all(long, boolean)} and checked its version. */
    public void update(long employeeId, long branchId, long actor, String role, String status, List<String> grants) {
        BranchMembership membership = branchMembershipRepository
                .findForUpdateByEmployeeIdAndBranchId(employeeId, branchId)
                .orElseThrow();
        long version = membership.getVersion();

        membership.change(BranchRole.valueOf(role), MembershipStatus.valueOf(status), actor);
        membership.replacePermissions(findDefinitions(grants));
        branchMembershipRepository.flush();

        if (membership.getVersion() == version) {
            branchMembershipRepository.incrementVersion(membership.getId());
        }

        entityManager.refresh(membership);
    }

    private LinkedHashSet<PermissionDefinition> findDefinitions(List<String> codes) {
        return new LinkedHashSet<>(permissionDefinitionRepository.findByCodeIn(codes));
    }

    private static MembershipRecord toRecord(BranchMembership membership, List<String> permissions) {
        return new MembershipRecord(
                membership.getId(),
                membership.getBranchId(),
                membership.getRole().name(),
                membership.getStatus().name(),
                permissions,
                membership.getVersion()
        );
    }
}
