package com.openstudy.modules.invitation.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.openstudy.modules.auth.service.SessionBoundaryPolicy;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.invitation.dto.ApprovalStatus;
import com.openstudy.modules.invitation.repository.ApprovalStatusRepository;

@Service
public class ApprovalStatusService {
    private final EmployeeAccountService employees;
    private final ApprovalStatusRepository repository;
    public ApprovalStatusService(EmployeeAccountService employees, ApprovalStatusRepository repository) {
        this.employees = employees;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ApprovalStatus current(long employeeId, String sessionMode) {
        var account = employees.byId(employeeId);
        return new ApprovalStatus("PENDING".equals(account.status()) ? "승인 대기" : "활성", repository.acceptedBy(employeeId),
                "ACTIVE".equals(account.status()) && SessionBoundaryPolicy.APPROVAL_ONLY.equals(sessionMode));
    }
}
