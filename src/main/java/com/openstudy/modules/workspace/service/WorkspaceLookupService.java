package com.openstudy.modules.workspace.service;

import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.PermissionPresetService;
import com.openstudy.modules.branch.service.BranchService;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.workspace.dto.WorkspaceLookups;

@Service
public class WorkspaceLookupService {
    private final AuthorizationService authorization;
    private final BranchService branches;
    private final EmployeeAccountService employees;
    private final PermissionPresetService permissions;
    private final Clock clock;
    public WorkspaceLookupService(AuthorizationService authorization, BranchService branches,
            EmployeeAccountService employees, PermissionPresetService permissions, Clock clock) {
        this.authorization=authorization; this.branches=branches; this.employees=employees;
        this.permissions=permissions; this.clock=clock;
    }

    @Transactional(readOnly=true)
    public WorkspaceLookups current(long actor, String scope) {
        var allowed=authorization.scope(actor,scope,BranchAction.READ);
        // Product and category management have not been implemented. Do not expose legacy draft rows as operational choices.
        String now=clock.instant().toString();
        return new WorkspaceLookups(branches.lookup(actor,scope),employees.lookup(allowed),List.of(),
                permissions.definitions(actor),permissions.lookup(actor,scope),List.of(),now,now);
    }
}
