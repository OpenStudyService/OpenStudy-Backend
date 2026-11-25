package com.openstudy.modules.employee.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.modules.employee.dto.EmployeeAccount;
import com.openstudy.modules.employee.dto.EmployeeMembership;
import com.openstudy.modules.employee.dto.EmployeeLookup;
import com.openstudy.modules.employee.dto.InvitationTarget;
import java.util.Optional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.dao.EmptyResultDataAccessException;
import com.openstudy.modules.authorization.dto.MembershipRecord;
import com.openstudy.modules.employee.entity.Employee;
import com.openstudy.modules.employee.entity.EmployeeStatus;
import com.openstudy.modules.employee.entity.GlobalRole;
import com.openstudy.modules.employee.repository.EmployeeRepository;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.authorization.service.MembershipService;
import com.openstudy.common.persistence.DuplicateKeys;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.StrictInput;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class EmployeeAccountService {

    private static final int MAX_NICKNAME_LENGTH = 100;
    private static final String PROFILE_PATH = "/api/me";

    private final EmployeeRepository employeeRepository;
    private final EmployeeChanges employeeChanges;
    private final AuthorizedCommands authorizedCommands;
    private final MembershipService membershipService;

    public EmployeeAccount byEmail(String email) {
        return employeeRepository.findByEmail(email.trim())
                .map(EmployeeChanges::toAccount)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    public boolean exists(String email) {
        return employeeRepository.findByEmail(email.trim()).isPresent();
    }

    public EmployeeAccount byId(long id) {
        return employeeRepository.findById(id)
                .map(EmployeeChanges::toAccount)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid account"));
    }

    /** Display names for actor summaries (who created a payment); unknown ids are simply absent. */
    public Map<Long, String> names(Collection<Long> ids) {
        Map<Long, String> result = new HashMap<>();
        employeeRepository.findAllById(Set.copyOf(ids))
                .forEach(employee -> result.put(employee.getId(), employee.getName()));

        return result;
    }

    public List<EmployeeMembership> memberships(long id) {
        return membershipService.active(id)
                .stream()
                .map(membership -> new EmployeeMembership(
                        Long.toString(membership.branchId()),
                        EmployeeChanges.toRoleLabel(membership.role()),
                        membership.permissions()
                ))
                .toList();
    }

    /** Caller holds AuthorizedCommands' guard, employee/branch and invitation parent locks. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<InvitationTarget> invitationTarget(String email, long branch) {
        // Same order as before: the account row FOR UPDATE, then the (employee, branch) membership row FOR UPDATE.
        return employeeRepository.findForUpdateByEmail(email)
                .map(EmployeeAccountService::toInvitationTarget)
                .map(account -> membershipService.lock(account.employeeId(), branch)
                        .map(membership -> withMembership(account, membership))
                        .orElse(account));
    }

    /** The invitation service verifies its ACCEPTED employee/branch before invoking this operation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokePendingInvitationMembership(long employee, long branch, long actor, String requestId) {
        if (!membershipService.revokePending(employee, branch, actor)) {
            return;
        }

        touch(employee, actor);
        employeeChanges.recordChange(
                actor,
                branch,
                employee,
                "REJECT_INVITATION",
                Map.of("status", "PENDING"),
                Map.of("status", "REVOKED"),
                requestId
        );
    }

    /** New-account acceptance. The caller verified the invitation token and holds the invitation lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long createInvitedAccount(String email, String passwordHash, String name) {
        Employee employee = new Employee(email, passwordHash, name, GlobalRole.EMPLOYEE, EmployeeStatus.PENDING);

        // Invitation acceptance maps a duplicate email (uk_employees_1) to "already registered".
        return DuplicateKeys.translate(() -> employeeRepository.saveAndFlush(employee)).getId();
    }

    /** Acceptance creates or reuses (Q11) only a pending membership; business access waits for approval. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void pendInvitationMembership(long employee, long branch, String role, String requestId) {
        membershipService.pend(employee, branch, role, employee);
        touch(employee, employee);
        employeeChanges.recordChange(
                employee,
                branch,
                employee,
                "ACCEPT_INVITATION",
                Map.of("status", "NONE_OR_REVOKED"),
                Map.of("status", "PENDING", "role", role),
                requestId
        );
    }

    /** The invitation service verified ACCEPTED state, account status and current authorization first. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void activateInvitationMembership(
            long employee,
            long branch,
            long actor,
            List<String> grants,
            String requestId
    ) {
        membershipService.activatePending(employee, branch, actor, grants);

        // Like the former UPDATE ... WHERE status IN ('PENDING','ACTIVE'): SUSPENDED/LEFT stay unchanged and unversioned.
        Employee account = employeeRepository.findById(employee).orElseThrow();
        long version = account.getVersion();

        if (account.approveInvited(actor)) {
            employeeChanges.saveVersion(account, version);
        }

        employeeChanges.recordChange(
                actor,
                branch,
                employee,
                "APPROVE_INVITATION",
                Map.of("status", "PENDING"),
                Map.of("status", "ACTIVE", "permissions", grants),
                requestId
        );
    }

    public List<EmployeeLookup> lookup(Set<Long> authorizedBranches) {
        if (authorizedBranches.isEmpty()) {
            return List.of();
        }

        return toLookups(employeeRepository.findLookups(authorizedBranches));
    }

    /** An OWNER without a membership row is excluded: work_tasks.assignee references branch_memberships. */
    public List<EmployeeLookup> assignees(long branch) {
        return toLookups(employeeRepository.findAssignees(branch));
    }

    public boolean assignable(long employee, long branch) {
        return employeeRepository.existsAssignable(employee, branch).intValue() == 1;
    }

    /** Employees a channel of the branch (null: a channel addressed to everyone) may have as members. */
    public List<EmployeeLookup> channelCandidates(Long branch) {
        return toLookups(employeeRepository.findChannelCandidates(branch));
    }

    public boolean channelEligible(long employee, Long branch) {
        return employeeRepository.existsChannelEligible(employee, branch).intValue() == 1;
    }

    @Transactional
    public void updateProfile(long id, Map<String, Object> body, String requestId) {
        authorizedCommands.selfAccountChange(id, () -> {
            StrictInput input = new StrictInput(body).only("nickname", "version");
            String nickname = input.text("nickname", MAX_NICKNAME_LENGTH);

            Employee account = findForUpdate(id);
            EmployeeAccount before = EmployeeChanges.toAccount(account);
            ApiVersion.requireCurrent(input.version("version"), before.version(), PROFILE_PATH);

            account.changeNickname(nickname);
            employeeChanges.saveVersion(account, before.version());

            EmployeeAccount after = EmployeeChanges.toAccount(account);
            employeeChanges.recordProfileChange(id, toProfileSnapshot(before), toProfileSnapshot(after), requestId);
            return null;
        });
    }

    /**
     * Sets or clears the own profile photo key (#120) and returns the key it replaced. The account version advances once,
     * like a profile save; the audit keeps only whether there is a photo, never the key.
     */
    @Transactional
    public String changeAvatar(long id, String key, String requestId) {
        return authorizedCommands.selfAccountChange(id, () -> {
            Employee account = findForUpdate(id);
            long version = account.getVersion();
            String previous = account.getAvatarUrl();

            account.changeAvatar(key);
            employeeChanges.saveVersion(account, version);

            String action = key == null ? "AVATAR_REMOVED" : "AVATAR_CHANGED";
            employeeChanges.recordChange(
                    id,
                    null,
                    id,
                    action,
                    Map.of("hasAvatar", previous != null),
                    Map.of("hasAvatar", key != null),
                    requestId
            );
            return previous;
        });
    }

    @Transactional
    public void recordLogin(long id) {
        employeeRepository.recordLogin(id);
    }

    @Transactional
    public void createLocalOwnerIfAbsent(String email, String encodedPassword) {
        if (employeeRepository.existsByGlobalRole(GlobalRole.OWNER)) {
            return;
        }

        employeeRepository.saveAndFlush(Employee.localOwner(email, encodedPassword));
    }

    private Employee findForUpdate(long id) {
        return employeeRepository.findForUpdateById(id)
                .orElseThrow(() -> new EmptyResultDataAccessException(1));
    }

    /** A membership of this employee changed: the account version advances with it, as before. */
    private void touch(long employee, long actor) {
        Employee account = employeeRepository.findById(employee).orElseThrow();
        long version = account.getVersion();

        account.touch(actor);
        employeeChanges.saveVersion(account, version);
    }

    private static Map<String, Object> toProfileSnapshot(EmployeeAccount account) {
        return Map.of(
                "nickname", account.nickname(),
                "version", ApiVersion.fromDatabase(account.version()),
                "updatedAt", account.updatedAt().toString()
        );
    }

    private static InvitationTarget toInvitationTarget(Employee employee) {
        boolean owner = employee.getGlobalRole() == GlobalRole.OWNER;

        return new InvitationTarget(employee.getId(), owner, employee.getStatus().name(), null, null, null);
    }

    private static InvitationTarget withMembership(InvitationTarget account, MembershipRecord membership) {
        return new InvitationTarget(
                account.employeeId(),
                account.owner(),
                account.accountStatus(),
                membership.id(),
                membership.role(),
                membership.status()
        );
    }

    private static List<EmployeeLookup> toLookups(List<Object[]> rows) {
        return rows.stream()
                .map(row -> new EmployeeLookup(row[0].toString(), (String) row[1]))
                .toList();
    }
}
