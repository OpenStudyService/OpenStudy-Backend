package com.openstudy.modules.invitation.service;

import java.util.EnumSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.openstudy.modules.auth.service.SessionBoundaryPolicy;
import com.openstudy.modules.branch.service.SessionBranchService;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.invitation.dto.ApprovalStatus;
import com.openstudy.modules.invitation.entity.EmployeeInvitation;
import com.openstudy.modules.invitation.entity.InvitationStatus;
import com.openstudy.modules.invitation.repository.ApprovalStatusRepository;

@Service
public class ApprovalStatusService {
    /** Invitations the employee has already accepted; PENDING/EXPIRED never carry an accepting employee. */
    private static final EnumSet<InvitationStatus> ACCEPTED = EnumSet.of(InvitationStatus.ACCEPTED,
            InvitationStatus.APPROVED, InvitationStatus.REJECTED, InvitationStatus.CANCELLED);
    private final EmployeeAccountService employees;
    private final ApprovalStatusRepository repository;
    private final SessionBranchService branches;
    public ApprovalStatusService(EmployeeAccountService employees, ApprovalStatusRepository repository,
            SessionBranchService branches) {
        this.employees = employees;
        this.repository = repository;
        this.branches = branches;
    }

    @Transactional(readOnly = true)
    public ApprovalStatus current(long employeeId, String sessionMode) {
        var account = employees.byId(employeeId);
        return new ApprovalStatus("PENDING".equals(account.status()) ? "승인 대기" : "활성", acceptedBy(employeeId),
                "ACTIVE".equals(account.status()) && SessionBoundaryPolicy.APPROVAL_ONLY.equals(sessionMode));
    }

    private List<ApprovalStatus.Item> acceptedBy(long employeeId) {
        var invitations = repository.findByAcceptedEmployeeIdAndAcceptedAtIsNotNullAndStatusInOrderByIdAsc(employeeId, ACCEPTED);
        // The invitee has no branch access yet, so names come from the session contract, not authorized branch reads.
        var names = branches.names(invitations.stream().map(EmployeeInvitation::getBranchId).distinct().toList());
        return invitations.stream().map(i -> new ApprovalStatus.Item(Long.toString(i.getId()), Long.toString(i.getBranchId()),
                names.get(i.getBranchId()), status(i.getStatus()), i.getRejectionReason(), i.getUpdatedAt().toString())).toList();
    }

    private static String status(InvitationStatus status) {
        return switch (status) {
            case ACCEPTED -> "승인 대기";
            case APPROVED -> "활성";
            case REJECTED -> "거절";
            case CANCELLED -> "취소";
            default -> throw new IllegalStateException("Invalid accepted invitation status");
        };
    }
}
