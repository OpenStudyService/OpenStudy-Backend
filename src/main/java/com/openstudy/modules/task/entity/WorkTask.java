package com.openstudy.modules.task.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Branch task. There is no delete (collaboration-api.md): task_events and shared cards reference this row. The branch
 * and creator never change; the assignee stays even if their membership is later revoked, and only a new assignee is
 * checked.
 */
@Entity
@Table(name = "work_tasks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkTask {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "branch_id", nullable = false, updatable = false)
    private long branchId;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(columnDefinition = "text")
    private String body;
    @Column(name = "created_by", nullable = false, updatable = false)
    private long createdBy;
    @Column(name = "assignee_employee_id", nullable = false)
    private long assigneeEmployeeId;
    @Column(nullable = false, length = 60)
    private String category;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private TaskPriority priority;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private TaskStatus status;
    @Column(name = "due_at")
    private Instant dueAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    // Both timestamps are DB-maintained (DEFAULT / ON UPDATE CURRENT_TIMESTAMP(6)).
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    /** Exposed to clients and checked on every task and checklist command. */
    @Version
    private long version;

    public WorkTask(long branchId, long createdBy, String title, String body, long assignee, String category,
            TaskPriority priority, TaskStatus status, Instant dueAt, Instant now) {
        this.branchId = branchId; this.createdBy = createdBy; this.title = title; this.body = body;
        this.assigneeEmployeeId = assignee; this.category = category; this.priority = priority; this.dueAt = dueAt;
        changeStatus(status, now);
    }

    public void changeContent(String title, String body, String category, TaskPriority priority) {
        this.title = title; this.body = body; this.category = category; this.priority = priority;
    }

    public void assign(long assignee) { this.assigneeEmployeeId = assignee; }

    public void reschedule(Instant dueAt) { this.dueAt = dueAt; }

    public void changeStatus(TaskStatus status, Instant now) {
        this.status = status;
        this.completedAt = status == TaskStatus.DONE ? now : null;
    }
}
