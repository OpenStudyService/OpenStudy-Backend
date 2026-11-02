package com.openstudy.modules.employee.dto;

/**
 * An employee lost access: a membership was revoked, or the account stopped being active. Published synchronously inside
 * the command's transaction, so a listener's work commits or rolls back with the change that caused it. An event keeps
 * the employee module free of a dependency on the modules that react, which already depend on it.
 */
public record EmployeeAccessReduced(long employeeId) {}
