package com.openstudy.modules.employee.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.openstudy.modules.employee.entity.Employee;
import com.openstudy.modules.employee.entity.EmployeeStatus;
import com.openstudy.modules.employee.entity.GlobalRole;
import com.openstudy.modules.employee.repository.EmployeeRepository;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.authorization.service.MembershipService;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.StrictInput;

@Service
@Transactional(readOnly = true)
public class EmployeeAccountService {
    private final EmployeeRepository repository;
    private final EmployeeChanges changes;
    private final AuthorizedCommands commands;
    private final MembershipService memberships;

    public EmployeeAccountService(EmployeeRepository repository, EmployeeChanges changes,
            AuthorizedCommands commands, MembershipService memberships) {
        this.repository = repository;
        this.changes = changes;
        this.commands = commands;
        this.memberships = memberships;
    }

    public EmployeeAccount byEmail(String email) {
        return repository.findByEmail(email.trim()).map(EmployeeChanges::account)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    public boolean exists(String email) {
        return repository.findByEmail(email.trim()).isPresent();
    }

    public EmployeeAccount byId(long id) {
        return repository.findById(id).map(EmployeeChanges::account).orElseThrow(() -> new UsernameNotFoundException("Invalid account"));
    }

    public List<EmployeeMembership> memberships(long id) {
        return memberships.active(id).stream().map(m -> new EmployeeMembership(Long.toString(m.branchId()),
                "MANAGER".equals(m.role()) ? "admin" : "staff", m.permissions())).toList();
    }

    /** Caller holds AuthorizedCommands' guard, employee/branch and invitation parent locks. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<InvitationTarget> invitationTarget(String email, long branch) {
        // Same order as before: the account row FOR UPDATE, then the (employee, branch) membership row FOR UPDATE.
        return repository.findForUpdateByEmail(email).map(e -> new InvitationTarget(e.getId(), e.getGlobalRole() == GlobalRole.OWNER,
                e.getStatus().name(), null, null, null)).map(account -> memberships.lock(account.employeeId(), branch)
                .map(m -> new InvitationTarget(account.employeeId(), account.owner(), account.accountStatus(), m.id(), m.role(), m.status()))
                .orElse(account));
    }

    /** The invitation service verifies its ACCEPTED employee/branch before invoking this operation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokePendingInvitationMembership(long employee, long branch, long actor, String requestId) {
        if (memberships.revokePending(employee, branch, actor)) {
            touch(employee, actor);
            changes.changed(actor, branch, employee, "REJECT_INVITATION", Map.of("status", "PENDING"),
                    Map.of("status", "REVOKED"), requestId);
        }
    }

    /** New-account acceptance. The caller verified the invitation token and holds the invitation lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long createInvitedAccount(String email, String passwordHash, String name) {
        return repository.saveAndFlush(new Employee(email, passwordHash, name, GlobalRole.EMPLOYEE, EmployeeStatus.PENDING)).getId();
    }

    /** Acceptance creates or reuses (Q11) only a pending membership; business access waits for approval. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void pendInvitationMembership(long employee, long branch, String role, String requestId) {
        memberships.pend(employee, branch, role, employee);
        touch(employee, employee);
        changes.changed(employee, branch, employee, "ACCEPT_INVITATION", Map.of("status", "NONE_OR_REVOKED"),
                Map.of("status", "PENDING", "role", role), requestId);
    }

    /** The invitation service verified ACCEPTED state, account status and current authorization first. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void activateInvitationMembership(long employee, long branch, long actor, List<String> grants, String requestId) {
        memberships.activatePending(employee, branch, actor, grants);
        // Like the former UPDATE ... WHERE status IN ('PENDING','ACTIVE'): SUSPENDED/LEFT stay unchanged and unversioned.
        var account = repository.findById(employee).orElseThrow();
        long version = account.getVersion();
        if (account.approveInvited(actor)) changes.saved(account, version);
        changes.changed(actor, branch, employee, "APPROVE_INVITATION", Map.of("status", "PENDING"),
                Map.of("status", "ACTIVE", "permissions", grants), requestId);
    }

    public List<EmployeeLookup> lookup(Set<Long> authorizedBranches) {
        if (authorizedBranches.isEmpty()) return List.of();
        return repository.lookup(authorizedBranches).stream()
                .map(row -> new EmployeeLookup(row[0].toString(), (String) row[1])).toList();
    }

    public boolean relatedToBranches(long employee, Set<Long> authorizedBranches) {
        if (authorizedBranches.isEmpty()) return false;
        return repository.relatedToBranches(employee, authorizedBranches).intValue() == 1;
    }

    @Transactional
    public void updateProfile(long id, Map<String, Object> body, String requestId) {
        commands.selfAccountChange(id, () -> {
            var input = new StrictInput(body).only("nickname", "version");
            String nickname = input.text("nickname", 100);
            var account = repository.findForUpdateById(id).orElseThrow(() -> new EmptyResultDataAccessException(1));
            var before = EmployeeChanges.account(account);
            ApiVersion.requireCurrent(input.version("version"), before.version(), "/api/me");
            account.changeNickname(nickname);
            changes.saved(account, before.version());
            changes.profileChanged(id, profileSnapshot(before), profileSnapshot(EmployeeChanges.account(account)), requestId);
            return null;
        });
    }

    private Map<String, Object> profileSnapshot(EmployeeAccount account) {
        return Map.of("nickname", account.nickname(), "version", ApiVersion.fromDatabase(account.version()),
                "updatedAt", account.updatedAt().toString());
    }

    @Transactional
    public void recordLogin(long id) {
        repository.recordLogin(id);
    }

    @Transactional
    public void createLocalOwnerIfAbsent(String email, String encodedPassword) {
        if (!repository.existsByGlobalRole(GlobalRole.OWNER)) repository.saveAndFlush(Employee.localOwner(email, encodedPassword));
    }

    /** A membership of this employee changed: the account version advances with it, as before. */
    private void touch(long employee, long actor) {
        var account = repository.findById(employee).orElseThrow();
        long version = account.getVersion();
        account.touch(actor);
        changes.saved(account, version);
    }
}
