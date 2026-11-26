package com.openstudy.modules.workspace.service;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.dto.PermissionDefinition;
import com.openstudy.modules.authorization.dto.PermissionPreset;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.PermissionPresetService;
import com.openstudy.modules.branch.dto.Branch;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.modules.employee.dto.EmployeeLookup;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.workspace.dto.WorkspaceLookups;

@Service
@RequiredArgsConstructor
public class WorkspaceLookupService {

    private final AuthorizationService authorizationService;
    private final BranchService branchService;
    private final EmployeeAccountService employeeAccountService;
    private final PermissionPresetService permissionPresetService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public WorkspaceLookups getLookups(long actor, String scope) {
        Set<Long> allowed = authorizationService.scope(actor, scope, BranchAction.READ);
        // Product and category management have not been implemented. Do not expose legacy draft rows as operational choices.
        String now = clock.instant().toString();

        List<Branch> branches = branchService.lookup(actor, scope);
        List<EmployeeLookup> employees = employeeAccountService.lookup(allowed);
        List<PermissionDefinition> permissionDefinitions = permissionPresetService.definitions(actor);
        List<PermissionPreset> permissionPresets = permissionPresetService.lookup(actor, scope);

        return new WorkspaceLookups(
                branches,
                employees,
                List.of(),
                permissionDefinitions,
                permissionPresets,
                List.of(),
                now,
                now
        );
    }
}
