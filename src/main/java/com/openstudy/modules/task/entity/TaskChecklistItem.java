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
import org.hibernate.generator.EventType;

/** Has no version of its own: every change advances the parent task's version instead. */
@Entity
@Table(name = "task_checklist_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskChecklistItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "task_id", nullable = false, updatable = false)
    private long taskId;
    @Column(nullable = false, length = 500)
    private String body;
    @Column(name = "sort_order", nullable = false, columnDefinition = "int unsigned", updatable = false)
    private int sortOrder;
    @Column(name = "is_done", nullable = false)
    private boolean done;
    @Column(name = "updated_by")
    private Long updatedBy;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public TaskChecklistItem(long taskId, String body, int sortOrder, long actor) {
        this.taskId = taskId; this.body = body; this.sortOrder = sortOrder; this.updatedBy = actor;
    }

    public void change(String body, boolean done, long actor) {
        this.body = body; this.done = done; this.updatedBy = actor;
    }
}
