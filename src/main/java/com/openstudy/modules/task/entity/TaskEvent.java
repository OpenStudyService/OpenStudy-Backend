package com.openstudy.modules.task.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/** Append-only processing history (FR-TASK-08). Only the actor can change, to NULL, when an account is removed. */
@Entity
@Table(name = "task_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "task_id", nullable = false, updatable = false)
    private long taskId;
    @Column(name = "actor_employee_id", updatable = false)
    private Long actorEmployeeId;
    @Column(name = "event_type", nullable = false, length = 50, updatable = false)
    private String eventType;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "before_json", updatable = false)
    private String beforeJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "after_json", updatable = false)
    private String afterJson;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public TaskEvent(long taskId, long actor, String eventType, String beforeJson, String afterJson) {
        this.taskId = taskId; this.actorEmployeeId = actor; this.eventType = eventType;
        this.beforeJson = beforeJson; this.afterJson = afterJson;
    }
}
