package com.openstudy.modules.auth.service;

import com.openstudy.modules.employee.dto.AvatarKeys;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.modules.auth.dto.CurrentUser;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.branch.service.SessionBranchService;
import com.openstudy.modules.employee.dto.EmployeeAccount;
import com.openstudy.modules.employee.dto.EmployeeMembership;
import com.openstudy.modules.employee.service.EmployeeAccountService;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CurrentUserService {
    private final EmployeeAccountService employeeAccountService;
    private final SessionBranchService sessionBranchService;
    private final Clock clock;

    @Transactional
    public CurrentUser updateProfile(
            long employeeId,
            String sessionMode,
            Map<String, Object> body,
            String requestId
    ) {
        if (!SessionBoundaryPolicy.WORKSPACE.equals(sessionMode)) {
            throw AuthorizationService.forbidden();
        }

        employeeAccountService.updateProfile(employeeId, body, requestId);

        return getCurrentUser(employeeId, sessionMode);

    }

    @Transactional(readOnly = true)
    public CurrentUser getCurrentUser(long employeeId) {
        return getCurrentUser(employeeId, null);
    }

    @Transactional(readOnly = true)
    public CurrentUser getCurrentUser(long employeeId, String sessionMode) {
        EmployeeAccount account = employeeAccountService.byId(employeeId);

        if (!account.canLogin()) {
            throw new DisabledException("Inactive account");
        }

        String mode = sessionMode;
        if (mode == null) {
            mode = "PENDING".equals(account.status())
                    ? SessionBoundaryPolicy.APPROVAL_ONLY
                    : SessionBoundaryPolicy.WORKSPACE;
        }

        boolean restricted = SessionBoundaryPolicy.APPROVAL_ONLY.equals(mode);

        List<EmployeeMembership> memberships = restricted
                ? List.of()
                : employeeAccountService.memberships(employeeId);

        String status = "PENDING".equals(account.status())
                ? "승인 대기"
                : "활성";

        var user = CurrentUser.User.builder()
                .id(Long.toString(account.id()))
                .branchId(memberships.isEmpty() ? "all" : memberships.getFirst().branchId())
                .version(ApiVersion.fromDatabase(account.version()))
                .createdAt(account.createdAt().toString())
                .updatedAt(account.updatedAt().toString())
                .name(account.name())
                .email(account.email())
                .status(status)
                .nickname(restricted ? "" : account.nickname())
                .avatar(restricted ? "" : AvatarKeys.path(account.id(), account.avatar()))
                .memberships(memberships)
                .owner(!restricted && account.owner())
                .build();

        return new CurrentUser(
                user,
                restricted
                        ? List.of()
                        : sessionBranchService.accessible(employeeId, account.owner()),
                clock.instant().toString(),
                "employee-" + account.id() + "-" + account.version(),
                mode
        );
    }
}
