package com.openstudy.modules.task.dto.response;

import java.time.Instant;
import java.util.List;

/** Contract schema {@code Task}. */
public record TaskDetail(String id, String branchId, String title, String category, String priority, String status,
        ActorSummary assignee, ActorSummary createdBy, Instant dueAt, Instant completedAt, boolean overdue,
        long checklistTotal, long checklistDone, boolean canEdit, long version, Instant createdAt, Instant updatedAt,
        String body, List<TaskChecklistItemView> checklist) {}
