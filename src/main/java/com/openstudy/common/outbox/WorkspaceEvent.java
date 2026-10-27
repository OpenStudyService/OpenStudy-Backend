package com.openstudy.common.outbox;

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
import org.hibernate.generator.EventType;

/**
 * A workspace refresh hint written in the same transaction as the change it describes.
 * Delivery columns (published_at, attempts) are only changed by bulk updates in {@link WorkspaceEventRepository}.
 */
@Entity
@Table(name = "workspace_event_outbox")
public class WorkspaceEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "branch_id", updatable = false)
    private Long branchId;
    @Column(name = "target_employee_id", updatable = false)
    private Long targetEmployeeId;
    @Column(name = "entity_type", nullable = false, length = 80, updatable = false)
    private String entityType;
    @Column(name = "entity_id", updatable = false)
    private Long entityId;
    @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false, updatable = false)
    private WorkspaceEventType eventType;
    @Generated(event = EventType.INSERT) @Column(name = "occurred_at", insertable = false, updatable = false)
    private Instant occurredAt;
    @Column(name = "published_at", insertable = false, updatable = false)
    private Instant publishedAt;
    @Column(insertable = false, updatable = false)
    private int attempts;
    @Column(name = "next_attempt_at", insertable = false, updatable = false)
    private Instant nextAttemptAt;

    protected WorkspaceEvent() {}

    WorkspaceEvent(Long branchId, Long targetEmployeeId, String entityType, Long entityId, WorkspaceEventType eventType) {
        this.branchId = branchId; this.targetEmployeeId = targetEmployeeId; this.entityType = entityType;
        this.entityId = entityId; this.eventType = eventType;
    }

    public Long getId() { return id; }
    public Long getBranchId() { return branchId; }
    public Long getTargetEmployeeId() { return targetEmployeeId; }
    public String getEntityType() { return entityType; }
    public Long getEntityId() { return entityId; }
    public WorkspaceEventType getEventType() { return eventType; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
