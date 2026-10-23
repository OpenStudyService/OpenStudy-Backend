package com.openstudy.modules.auth.service;

import java.time.Clock;
import java.util.List;

import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.modules.auth.dto.CurrentUser;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.modules.branch.service.SessionBranchService;
import com.openstudy.modules.employee.service.EmployeeAccountService;

@Service
public class CurrentUserService {
    private final EmployeeAccountService employees;
    private final SessionBranchService branches;
    private final Clock clock;

    public CurrentUserService(EmployeeAccountService employees, SessionBranchService branches, Clock clock) {
        this.employees = employees;
        this.branches = branches;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CurrentUser current(long employeeId) {
        return current(employeeId, null);
    }

    @Transactional(readOnly = true)
    public CurrentUser current(long employeeId, String sessionMode) {
        var account = employees.byId(employeeId);
        if (!account.canLogin()) throw new DisabledException("Inactive account");
        String mode = sessionMode == null
                ? ("PENDING".equals(account.status()) ? SessionBoundaryPolicy.APPROVAL_ONLY : SessionBoundaryPolicy.WORKSPACE)
                : sessionMode;
        boolean restricted = SessionBoundaryPolicy.APPROVAL_ONLY.equals(mode);
        var memberships = restricted ? List.<com.openstudy.modules.employee.dto.EmployeeMembership>of() : employees.memberships(employeeId);
        String status = "PENDING".equals(account.status()) ? "승인 대기" : "활성";
        var user = new CurrentUser.User(Long.toString(account.id()),
                memberships.isEmpty() ? "all" : memberships.getFirst().branchId(), ApiVersion.fromDatabase(account.version()),
                account.createdAt().toString(), account.updatedAt().toString(), account.name(), account.email(),
                status, restricted ? "" : account.nickname(), restricted || account.avatar() == null ? "" : account.avatar(), memberships,
                !restricted && account.owner());
        return new CurrentUser(user, restricted ? List.of() : branches.accessible(employeeId, account.owner()), clock.instant().toString(),
                "employee-" + account.id() + "-" + account.version(), mode);
    }
}
