package com.openstudy.modules.invitation.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.repository.Repository;
import com.openstudy.modules.invitation.entity.EmployeeInvitation;
import com.openstudy.modules.invitation.entity.InvitationStatus;

public interface ApprovalStatusRepository extends Repository<EmployeeInvitation, Long> {
    List<EmployeeInvitation> findByAcceptedEmployeeIdAndAcceptedAtIsNotNullAndStatusInOrderByIdAsc(long employeeId,
            Collection<InvitationStatus> statuses);
}
