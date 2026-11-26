package com.openstudy.modules.invitation.service;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.openstudy.modules.auth.service.SessionBoundaryPolicy;
import com.openstudy.modules.branch.service.SessionBranchService;
import com.openstudy.modules.employee.dto.EmployeeAccount;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.invitation.dto.ApprovalStatus;
import com.openstudy.modules.invitation.entity.EmployeeInvitation;
import com.openstudy.modules.invitation.entity.InvitationStatus;
import com.openstudy.modules.invitation.repository.ApprovalStatusRepository;

@Service
@RequiredArgsConstructor
public class ApprovalStatusService {

    /** Invitations the employee has already accepted; PENDING/EXPIRED never carry an accepting employee. */
    private static final EnumSet<InvitationStatus> ACCEPTED = EnumSet.of(
            InvitationStatus.ACCEPTED,
            InvitationStatus.APPROVED,
            InvitationStatus.REJECTED,
            InvitationStatus.CANCELLED
    );

    private final EmployeeAccountService employeeAccountService;
    private final ApprovalStatusRepository approvalStatusRepository;
    private final SessionBranchService sessionBranchService;

    @Transactional(readOnly = true)
    public ApprovalStatus current(long employeeId, String sessionMode) {
        EmployeeAccount account = employeeAccountService.byId(employeeId);
        String accountStatus = "PENDING".equals(account.status()) ? "승인 대기" : "활성";
        List<ApprovalStatus.Item> invitations = findAcceptedBy(employeeId);
        boolean requiresLogin = "ACTIVE".equals(account.status())
                && SessionBoundaryPolicy.APPROVAL_ONLY.equals(sessionMode);

        return new ApprovalStatus(accountStatus, invitations, requiresLogin);
    }

    private List<ApprovalStatus.Item> findAcceptedBy(long employeeId) {
        List<EmployeeInvitation> invitations = approvalStatusRepository
                .findByAcceptedEmployeeIdAndAcceptedAtIsNotNullAndStatusInOrderByIdAsc(employeeId, ACCEPTED);
        List<Long> branchIds = invitations.stream()
                .map(EmployeeInvitation::getBranchId)
                .distinct()
                .toList();

        // The invitee has no branch access yet, so names come from the session contract, not authorized branch reads.
        Map<Long, String> branchNames = sessionBranchService.names(branchIds);

        return invitations.stream()
                .map(invitation -> new ApprovalStatus.Item(
                        Long.toString(invitation.getId()),
                        Long.toString(invitation.getBranchId()),
                        branchNames.get(invitation.getBranchId()),
                        toStatusLabel(invitation.getStatus()),
                        invitation.getRejectionReason(),
                        invitation.getUpdatedAt().toString()
                ))
                .toList();
    }

    private static String toStatusLabel(InvitationStatus status) {
        return switch (status) {
            case ACCEPTED -> "승인 대기";
            case APPROVED -> "활성";
            case REJECTED -> "거절";
            case CANCELLED -> "취소";
            default -> throw new IllegalStateException("Invalid accepted invitation status");
        };
    }
}
