package com.openstudy.modules.workspace.dto;

import java.util.List;
import com.openstudy.modules.branch.dto.Branch;
import com.openstudy.modules.employee.dto.EmployeeLookup;
import com.openstudy.modules.authorization.dto.PermissionDefinition;
import com.openstudy.modules.authorization.dto.PermissionPreset;

public record WorkspaceLookups(List<Branch> branches, List<EmployeeLookup> employees, List<Object> products,
        List<PermissionDefinition> permissionDefinitions, List<PermissionPreset> permissionPresets,
        List<Object> productCategories, String asOf, String snapshotVersion) {}
