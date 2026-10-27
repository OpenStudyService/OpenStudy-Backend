package com.openstudy.common.audit;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * Append-only audit row. JSON snapshots arrive already serialized and stripped of sensitive fields by the caller;
 * they are stored as given (LONG32VARCHAR binds the text as-is instead of re-encoding it as a JSON string).
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "actor_employee_id", updatable = false)
    private Long actorEmployeeId;
    @Column(name = "branch_id", updatable = false)
    private Long branchId;
    @Column(name = "entity_type", nullable = false, length = 80, updatable = false)
    private String entityType;
    @Column(name = "entity_id", updatable = false)
    private Long entityId;
    @Column(nullable = false, length = 80, updatable = false)
    private String action;
    @Column(length = 500, updatable = false)
    private String reason;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "before_json", updatable = false)
    private String beforeJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "after_json", updatable = false)
    private String afterJson;
    @Generated(event = EventType.INSERT) @Column(name = "occurred_at", insertable = false, updatable = false)
    private Instant occurredAt;
    @Column(name = "request_id", length = 128, updatable = false)
    private String requestId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false)
    private AuditResult result;

    protected AuditEvent() {}

    private AuditEvent(Long actor, Long branch, String entityType, Long entityId, String action, String reason,
            String beforeJson, String afterJson, String requestId, AuditResult result) {
        this.actorEmployeeId = actor; this.branchId = branch; this.entityType = entityType; this.entityId = entityId;
        this.action = action; this.reason = reason; this.beforeJson = beforeJson; this.afterJson = afterJson;
        this.requestId = requestId; this.result = result;
    }

    /** A successful state change; actor is null for system work such as the invitation mail worker. */
    public static AuditEvent change(Long actor, Long branch, String entityType, Long entityId, String action,
            String beforeJson, String afterJson, String requestId) {
        return new AuditEvent(actor, branch, entityType, entityId, action, null, beforeJson, afterJson, requestId,
                AuditResult.SUCCESS);
    }

    /** An authentication attempt; no snapshots, the outcome is carried by reason and result. */
    public static AuditEvent attempt(Long actor, String entityType, Long entityId, String action, String reason,
            String requestId, AuditResult result) {
        return new AuditEvent(actor, null, entityType, entityId, action, reason, null, null, requestId, result);
    }

    public Long getId() { return id; }
    public Long getActorEmployeeId() { return actorEmployeeId; }
    public Long getBranchId() { return branchId; }
    public String getEntityType() { return entityType; }
    public Long getEntityId() { return entityId; }
    public String getAction() { return action; }
    public String getReason() { return reason; }
    public String getBeforeJson() { return beforeJson; }
    public String getAfterJson() { return afterJson; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getRequestId() { return requestId; }
    public AuditResult getResult() { return result; }
}
