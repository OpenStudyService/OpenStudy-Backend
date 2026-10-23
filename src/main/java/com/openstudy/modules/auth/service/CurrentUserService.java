package com.openstudy.modules.auth.service;

import java.time.Instant;

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

    public CurrentUserService(EmployeeAccountService employees, SessionBranchService branches) {
        this.employees = employees;
        this.branches = branches;
    }

    @Transactional(readOnly = true)
    public CurrentUser current(long employeeId) {
        var account = employees.byId(employeeId);
        if (!account.canLogin()) throw new DisabledException("Inactive account");
        var memberships = employees.memberships(employeeId);
        String status = "PENDING".equals(account.status()) ? "승인 대기" : "활성";
        var user = new CurrentUser.User(Long.toString(account.id()),
                memberships.isEmpty() ? "all" : memberships.getFirst().branchId(), ApiVersion.fromDatabase(account.version()),
                account.createdAt().toString(), account.updatedAt().toString(), account.name(), account.email(),
                status, account.nickname(), account.avatar() == null ? "" : account.avatar(), memberships, account.owner());
        return new CurrentUser(user, branches.accessible(employeeId, account.owner()), Instant.now().toString(),
                "employee-" + account.id() + "-" + account.version());
    }
}
