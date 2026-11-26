package com.openstudy.modules.employee.service;

import com.openstudy.modules.employee.dto.AvatarKeys;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.*;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.dto.MembershipRecord;
import com.openstudy.modules.authorization.service.*;
import com.openstudy.modules.employee.dto.EmployeeAccessReduced;
import com.openstudy.modules.employee.dto.ManagedEmployee;
import com.openstudy.modules.employee.dto.ManagedEmployee.Membership;
import com.openstudy.modules.employee.dto.ManagedEmployee.MembershipResult;
import com.openstudy.modules.employee.entity.Employee;
import com.openstudy.modules.employee.entity.EmployeeStatus;
import com.openstudy.modules.employee.entity.GlobalRole;
import com.openstudy.modules.employee.repository.EmployeeRepository;

@Service
@RequiredArgsConstructor
public class EmployeeManagementService {

    private static final Set<String> STATUS_LABELS = Set.of("활성", "승인 대기", "이용 제한", "비활성");
    private static final String ALL_SCOPE = "all";
    private static final int MAX_NAME_LENGTH = 100;

    private final EmployeeRepository employeeRepository;
    private final EmployeeChanges employeeChanges;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final PermissionPresetService permissionPresetService;
    private final MembershipService membershipService;
    private final ApplicationEventPublisher applicationEventPublisher;

    private record Visibility(Set<Long> branches, boolean all) {
    }

    @Transactional(readOnly = true)
    public PageResponse<ManagedEmployee> list(long actor, String scope, ListQuery query, String status) {
        if (status != null && !STATUS_LABELS.contains(status)) {
            throw ApiException.validation("status", "올바른 상태를 선택해 주세요.");
        }

        Visibility visibility = findVisibility(actor, scope);
        String keyword = query.q().toLowerCase(Locale.ROOT);

        List<ManagedEmployee> rows = employeeRepository.findAllIds()
                .stream()
                .map(id -> findEmployee(id, visibility.branches(), visibility.all(), false).orElseThrow())
                .filter(employee -> visibility.all() || !employee.memberships().isEmpty())
                .filter(employee -> status == null || status.equals(employee.status()))
                .filter(employee -> matchesKeyword(employee, keyword))
                .toList();

        Comparator<ManagedEmployee> order = "name".equals(query.sort())
                ? Comparator.comparing(ManagedEmployee::name)
                : Comparator.comparing(ManagedEmployee::createdAt);
        Comparator<ManagedEmployee> tieBreaker = Comparator.comparingLong(employee -> Long.parseLong(employee.id()));

        return PageResponse.fromAuthorized(rows, query, order, tieBreaker);
    }

    @Transactional(readOnly = true)
    public ManagedEmployee get(long actor, long target, String scope) {
        Visibility visibility = findVisibility(actor, scope);
        ManagedEmployee employee = findEmployee(target, visibility.branches(), visibility.all(), false)
                .orElseThrow(EmployeeManagementService::notFound);

        boolean hiddenFromActor = !visibility.all() && employee.memberships().isEmpty();

        if (hiddenFromActor) {
            throw notFound();
        }

        return employee;
    }

    public ManagedEmployee update(long actor, long target, Map<String, Object> body, String requestId) {
        return authorizedCommands.accountChange(actor, target, () -> {
            ManagedEmployee before = findEmployee(target, Set.of(), true, true)
                    .orElseThrow(EmployeeManagementService::notFound);
            long dbVersionBefore = before.version() - 1;

            StrictInput input = new StrictInput(body).only("version", "name", "status");
            ApiVersion.requireCurrent(input.version("version"), dbVersionBefore, "/api/employees/" + target);

            if (!input.has("name") && !input.has("status")) {
                throw ApiException.validation("body", "변경할 값을 입력해 주세요.");
            }

            String name = input.has("name") ? input.text("name", MAX_NAME_LENGTH) : before.name();
            String status = input.has("status")
                    ? input.choice("status", "활성", "이용 제한", "비활성")
                    : before.status();
            boolean activatingPending = "승인 대기".equals(before.status()) && "활성".equals(status);

            if (activatingPending) {
                throw ApiException.conflict("INVALID_STATE", "초대 승인으로만 활성화할 수 있습니다.");
            }

            String dbStatus = toDbStatus(status);
            Employee account = employeeRepository.findForUpdateById(target).orElseThrow();
            account.changeAccount(name, EmployeeStatus.valueOf(dbStatus), actor);
            employeeChanges.saveVersion(account, dbVersionBefore);

            // Every session of a disabled account ends now;
            // requests are independently guarded against current status too.
            if (!"ACTIVE".equals(dbStatus)) {
                employeeRepository.deleteSessions(Long.toString(target));
                applicationEventPublisher.publishEvent(new EmployeeAccessReduced(target));
            }

            ManagedEmployee result = findEmployee(target, Set.of(), true, true).orElseThrow();
            employeeChanges.recordChange(actor, null, target, "UPDATE_ACCOUNT", before, result, requestId);
            return result;
        });
    }

    public MembershipResult updateMembership(
            long actor,
            long target,
            long branch,
            String scope,
            Map<String, Object> body,
            String requestId
    ) {
        StrictInput input = new StrictInput(body)
                .only("employeeVersion", "version", "role", "status", "permissions", "presetId");
        String role = input.choice("role", "admin", "staff");
        String status = input.choice("status", "활성", "회수");
        String dbRole = "admin".equals(role) ? "MANAGER" : "STAFF";
        String dbStatus = "활성".equals(status) ? "ACTIVE" : "REVOKED";

        return authorizedCommands.membershipChange(actor, target, branch, dbRole, dbStatus, () -> {
            // Path scope is authoritative; an unrelated query scope must not broaden it.
            Visibility visibility = findVisibility(actor, scope);

            if (!visibility.branches().contains(branch)) {
                throw AuthorizationService.forbidden();
            }

            ManagedEmployee before = findEmployee(target, Set.of(branch), false, true)
                    .orElseThrow(EmployeeManagementService::notFound);
            Membership membershipBefore = before.memberships()
                    .stream()
                    .filter(membership -> membership.branchId().equals(Long.toString(branch)))
                    .findFirst()
                    .orElseThrow(EmployeeManagementService::notFound);

            String path = "/api/employees/" + target + "?scope=" + branch;
            long dbVersionBefore = before.version() - 1;
            ApiVersion.requireCurrent(input.version("employeeVersion"), dbVersionBefore, path);
            ApiVersion.requireCurrent(input.version("version"), membershipBefore.version() - 1, path);

            List<String> grants = input.permissions();
            Long presetId = input.has("presetId") ? input.id("presetId") : null;
            permissionPresetService.validateCopy(role, grants, presetId);
            membershipService.update(target, branch, actor, dbRole, dbStatus, grants);

            Employee account = employeeRepository.findForUpdateById(target).orElseThrow();
            account.touch(actor);
            employeeChanges.saveVersion(account, dbVersionBefore);

            if ("REVOKED".equals(dbStatus)) {
                applicationEventPublisher.publishEvent(new EmployeeAccessReduced(target));
            }

            ManagedEmployee after = findEmployee(target, Set.of(branch), false, true).orElseThrow();
            MembershipResult result = new MembershipResult(after.version(), after.memberships().getFirst());
            MembershipResult previous = new MembershipResult(before.version(), membershipBefore);
            employeeChanges.recordChange(actor, branch, target, "UPDATE_MEMBERSHIP", previous, result, requestId);
            return result;
        });
    }

    /** The former single read: the account row (FOR UPDATE when locking), then its memberships and grants. */
    private Optional<ManagedEmployee> findEmployee(long id, Set<Long> visible, boolean all, boolean lock) {
        Optional<Employee> account = lock
                ? employeeRepository.findForUpdateById(id)
                : employeeRepository.findById(id);

        return account.map(employee -> {
            List<Membership> memberships = membershipService.all(id, lock)
                    .stream()
                    .filter(membership -> all || visible.contains(membership.branchId()))
                    .map(EmployeeManagementService::toMembership)
                    .toList();

            return toManagedEmployee(employee, memberships);
        });
    }

    private Visibility findVisibility(long actor, String scope) {
        AuthorizationState state = authorizationService.current(actor);
        Set<Long> branches = authorizationService.scope(state, scope, BranchAction.MANAGE_PEOPLE);

        if (!state.owner() && branches.isEmpty()) {
            throw AuthorizationService.forbidden();
        }

        boolean allBranches = state.owner() && (scope == null || ALL_SCOPE.equals(scope));
        return new Visibility(branches, allBranches);
    }

    private static boolean matchesKeyword(ManagedEmployee employee, String keyword) {
        String searchable = employee.name() + " " + employee.email();

        return searchable.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private static Membership toMembership(MembershipRecord membership) {
        return new Membership(
                Long.toString(membership.branchId()),
                EmployeeChanges.toRoleLabel(membership.role()),
                EmployeeChanges.toMembershipStatusLabel(membership.status()),
                membership.permissions(),
                ApiVersion.fromDatabase(membership.dbVersion())
        );
    }

    private static ManagedEmployee toManagedEmployee(Employee employee, List<Membership> memberships) {
        String branchId = memberships.isEmpty() ? ALL_SCOPE : memberships.getFirst().branchId();

        return new ManagedEmployee(
                Long.toString(employee.getId()),
                ApiVersion.fromDatabase(employee.getVersion()),
                employee.getCreatedAt(),
                employee.getUpdatedAt(),
                branchId,
                employee.getName(),
                employee.getEmail(),
                EmployeeChanges.toStatusLabel(employee.getStatus().name()),
                employee.getNickname(),
                AvatarKeys.path(employee.getId(), employee.getAvatarUrl()),
                employee.getGlobalRole() == GlobalRole.OWNER,
                memberships
        );
    }

    private static String toDbStatus(String statusLabel) {
        return switch (statusLabel) {
            case "활성" -> "ACTIVE";
            case "이용 제한" -> "SUSPENDED";
            case "비활성" -> "LEFT";
            case "승인 대기" -> "PENDING";
            default -> throw new IllegalStateException();
        };
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
