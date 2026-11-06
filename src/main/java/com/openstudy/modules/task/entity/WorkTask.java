package com.openstudy.modules.task.entity;

import java.time.Instant;

import com.openstudy.domainlens.api.semantic.SemanticEntity;
import com.openstudy.domainlens.api.semantic.SemanticField;
import com.openstudy.domainlens.api.semantic.SemanticPolicy;
import com.openstudy.domainlens.api.semantic.SemanticRelation;
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
@SemanticEntity(
        id = "work_task",
        name = "업무",
        description = "지점별 운영 할 일 및 긴급 점검 업무 원장",
        aliases = {"할 일", "작업", "긴급 업무", "미완료 업무"},
        domain = "tasks"
)
@SemanticPolicy(
        readPolicy = "openstudy.read.ai",
        rowPolicy = "openstudy.branchScope",
        fieldPolicy = "openstudy.field.public"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkTask {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @SemanticField(id = "work_task.id", name = "업무 ID", role = "ID", allowedOps = {"EQ", "NE", "IN"})
    private Long id;
    @Column(name = "branch_id", nullable = false, updatable = false)
    @SemanticField(id = "work_task.branch_id", name = "지점 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "work_task.branch", targetEntity = "branch", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "업무 소속 지점")
    private long branchId;
    @Column(nullable = false, length = 200)
    @SemanticField(id = "work_task.title", name = "업무 제목", role = "LABEL", allowedOps = {"EQ", "NE", "IN"})
    private String title;
    @Column(columnDefinition = "text")
    @SemanticField(id = "work_task.body", name = "업무 상세 내용", exposed = false)
    private String body;
    @Column(name = "created_by", nullable = false, updatable = false)
    private long createdBy;
    @Column(name = "assignee_employee_id", nullable = false)
    @SemanticField(id = "work_task.assignee_employee_id", name = "담당 직원 ID", role = "FK", allowedOps = {"EQ", "NE", "IN"})
    @SemanticRelation(id = "work_task.assignee", targetEntity = "employee", targetField = "id", direction = "OUTGOING", joinUse = "APPROVED", meaning = "업무 담당 직원")
    private long assigneeEmployeeId;
    @Column(nullable = false, length = 60)
    @SemanticField(id = "work_task.category", name = "업무 카테고리", role = "CATEGORY", allowedOps = {"EQ", "NE", "IN"})
    private String category;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    @SemanticField(id = "work_task.priority", name = "우선순위", role = "STATUS", allowedValues = {"LOW", "NORMAL", "HIGH", "URGENT"}, aliases = {"긴급", "높음"}, allowedOps = {"EQ", "NE", "IN"})
    private TaskPriority priority;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    @SemanticField(id = "work_task.status", name = "업무 상태", role = "STATUS", allowedValues = {"TODO", "IN_PROGRESS", "DONE"}, aliases = {"미완료", "진행중", "완료"}, allowedOps = {"EQ", "NE", "IN"})
    private TaskStatus status;
    @Column(name = "due_at")
    @SemanticField(id = "work_task.due_at", name = "마감 기한", role = "TIME", aliases = {"기한", "마감일"}, allowedOps = {"EQ", "NE", "GT", "GTE", "LT", "LTE", "BETWEEN", "IS_NULL"})
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
