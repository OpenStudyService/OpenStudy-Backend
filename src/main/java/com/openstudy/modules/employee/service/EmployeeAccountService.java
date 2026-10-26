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
import com.openstudy.modules.employee.repository.EmployeeAccountRepository;
import com.openstudy.modules.employee.repository.EmployeeManagementRepository;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.StrictInput;

@Service
@Transactional(readOnly = true)
public class EmployeeAccountService {
    private final EmployeeAccountRepository repository;
    private final EmployeeManagementRepository changes;
    private final AuthorizedCommands commands;

    public EmployeeAccountService(EmployeeAccountRepository repository, EmployeeManagementRepository changes,
            AuthorizedCommands commands) {
        this.repository = repository;
        this.changes = changes;
        this.commands = commands;
    }

    public EmployeeAccount byEmail(String email) {
        return repository.findByEmail(email.trim()).orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    public EmployeeAccount byId(long id) {
        return repository.findById(id).orElseThrow(() -> new UsernameNotFoundException("Invalid account"));
    }

    public List<EmployeeMembership> memberships(long id) {
        return repository.memberships(id);
    }

    /** Caller holds AuthorizedCommands' guard, employee/branch and invitation parent locks. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<InvitationTarget> invitationTarget(String email, long branch) {
        return repository.invitationTarget(email, branch);
    }

    /** The invitation service verifies its ACCEPTED employee/branch before invoking this operation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokePendingInvitationMembership(long employee, long branch, long actor, String requestId) {
        if (repository.revokePendingInvitationMembership(employee, branch, actor)) {
            changes.changed(actor, branch, employee, "REJECT_INVITATION", Map.of("status", "PENDING"),
                    Map.of("status", "REVOKED"), requestId);
        }
    }

    public List<EmployeeLookup> lookup(Set<Long> authorizedBranches) { return repository.lookup(authorizedBranches); }

    public boolean relatedToBranches(long employee, Set<Long> authorizedBranches) {
        return repository.relatedToBranches(employee, authorizedBranches);
    }

    @Transactional
    public void updateProfile(long id, Map<String, Object> body, String requestId) {
        commands.selfAccountChange(id, () -> {
            var input = new StrictInput(body).only("nickname", "version");
            String nickname = input.text("nickname", 100);
            var before = repository.lockById(id);
            ApiVersion.requireCurrent(input.version("version"), before.version(), "/api/me");
            repository.updateNickname(id, nickname);
            changes.profileChanged(id, profileSnapshot(before), profileSnapshot(repository.lockById(id)), requestId);
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
        if (!repository.ownerExists()) repository.createOwner(email, encodedPassword);
    }
}
