package com.openstudy.modules.employee.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.modules.employee.dto.EmployeeAccount;
import com.openstudy.modules.employee.entity.Employee;
import com.openstudy.modules.employee.repository.EmployeeRepository;

/** Shared write helpers of the employee services: version advance, audit records and display codes. */
@Component
@RequiredArgsConstructor
class EmployeeChanges {

    private static final String AUDIT_ENTITY_TYPE = "employee";

    private final EmployeeRepository employeeRepository;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final AuditTrail auditTrail;

    /**
     * Every account command advances the version by exactly one, even when no column differs (the former SQL always
     * ran version=version+1). Hibernate only bumps a dirty row, so an unchanged one gets one explicit increment.
     * The refresh re-reads the DB-maintained updated_at.
     */
    void saveVersion(Employee employee, long versionBefore) {
        employeeRepository.flush();

        if (employee.getVersion() == versionBefore) {
            employeeRepository.incrementVersion(employee.getId());
        }

        entityManager.refresh(employee);
    }

    void recordChange(
            long actor,
            Long branch,
            long target,
            String action,
            Object before,
            Object after,
            String requestId
    ) {
        AuditEvent event = AuditEvent.change(
                actor,
                branch,
                AUDIT_ENTITY_TYPE,
                target,
                action,
                objectMapper.writeValueAsString(before),
                objectMapper.writeValueAsString(after),
                requestId
        );

        auditTrail.record(event);
    }

    void recordProfileChange(long actor, Object before, Object after, String requestId) {
        recordChange(actor, null, actor, "UPDATE_PROFILE", before, after, requestId);
    }

    static EmployeeAccount toAccount(Employee employee) {
        return new EmployeeAccount(
                employee.getId(),
                employee.getEmail(),
                employee.getPasswordHash(),
                employee.getName(),
                employee.getNickname(),
                employee.getAvatarUrl(),
                employee.getGlobalRole().name(),
                employee.getStatus().name(),
                employee.getVersion(),
                employee.getCreatedAt(),
                employee.getUpdatedAt()
        );
    }

    static String toRoleLabel(String role) {
        return "MANAGER".equals(role) ? "admin" : "staff";
    }

    static String toStatusLabel(String status) {
        return switch (status) {
            case "ACTIVE" -> "활성";
            case "PENDING" -> "승인 대기";
            case "SUSPENDED" -> "이용 제한";
            case "LEFT" -> "비활성";
            default -> throw new IllegalStateException();
        };
    }

    static String toMembershipStatusLabel(String status) {
        return switch (status) {
            case "ACTIVE" -> "활성";
            case "PENDING" -> "승인 대기";
            case "REVOKED" -> "회수";
            default -> throw new IllegalStateException();
        };
    }
}
