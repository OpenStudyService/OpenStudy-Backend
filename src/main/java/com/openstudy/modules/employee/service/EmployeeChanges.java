package com.openstudy.modules.employee.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.outbox.WorkspaceEventType;
import com.openstudy.common.outbox.WorkspaceOutbox;
import com.openstudy.modules.employee.dto.EmployeeAccount;
import com.openstudy.modules.employee.entity.Employee;
import com.openstudy.modules.employee.repository.EmployeeRepository;

/** Shared write helpers of the employee services: version advance, audit/outbox records and display codes. */
@Component
class EmployeeChanges {
    private final EmployeeRepository employees;
    private final EntityManager entityManager;
    private final ObjectMapper json;
    private final AuditTrail audit;
    private final WorkspaceOutbox outbox;

    EmployeeChanges(EmployeeRepository employees, EntityManager entityManager, ObjectMapper json, AuditTrail audit,
            WorkspaceOutbox outbox) {
        this.employees = employees; this.entityManager = entityManager; this.json = json; this.audit = audit; this.outbox = outbox;
    }

    /**
     * Every account command advances the version by exactly one, even when no column differs (the former SQL always
     * ran version=version+1). Hibernate only bumps a dirty row, so an unchanged one gets one explicit increment.
     * The refresh re-reads the DB-maintained updated_at.
     */
    void saved(Employee employee, long versionBefore) {
        employees.flush();
        if (employee.getVersion() == versionBefore) employees.incrementVersion(employee.getId());
        entityManager.refresh(employee);
    }

    void changed(long actor, Long branch, long target, String action, Object before, Object after, String requestId) {
        changed(actor, branch, target, action, before, after, requestId, WorkspaceEventType.AUTHORIZATION_CHANGED);
    }

    void profileChanged(long actor, Object before, Object after, String requestId) {
        changed(actor, null, actor, "UPDATE_PROFILE", before, after, requestId, WorkspaceEventType.INVALIDATE);
    }

    private void changed(long actor, Long branch, long target, String action, Object before, Object after, String requestId,
            WorkspaceEventType eventType) {
        audit.record(AuditEvent.change(actor, branch, "employee", target, action,
                json.writeValueAsString(before), json.writeValueAsString(after), requestId));
        outbox.notifyEmployee(branch, target, "employee", target, eventType);
        outbox.invalidate(branch, "employees", target);
    }

    static EmployeeAccount account(Employee e) {
        return new EmployeeAccount(e.getId(), e.getEmail(), e.getPasswordHash(), e.getName(), e.getNickname(), e.getAvatarUrl(),
                e.getGlobalRole().name(), e.getStatus().name(), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }

    static String status(String db) { return switch(db) { case "ACTIVE" -> "활성"; case "PENDING" -> "승인 대기"; case "SUSPENDED" -> "이용 제한"; case "LEFT" -> "비활성"; default -> throw new IllegalStateException(); }; }
    static String membershipStatus(String db) { return switch(db) { case "ACTIVE" -> "활성"; case "PENDING" -> "승인 대기"; case "REVOKED" -> "회수"; default -> throw new IllegalStateException(); }; }
}
