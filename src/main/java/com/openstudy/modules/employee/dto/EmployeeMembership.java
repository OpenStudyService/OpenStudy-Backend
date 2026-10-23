package com.openstudy.modules.employee.dto;

import java.util.List;

public record EmployeeMembership(String branchId, String role, List<String> permissions) {
}
