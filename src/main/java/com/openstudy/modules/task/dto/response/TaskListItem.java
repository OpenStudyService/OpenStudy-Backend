package com.openstudy.modules.task.dto.response;

import java.time.Instant;

/** Contract schema {@code TaskListItem}. canEdit only guides the screen; every command checks again. */
public record TaskListItem(String id, String branchId, String title, String category, String priority, String status,
        ActorSummary assignee, ActorSummary createdBy, Instant dueAt, Instant completedAt, boolean overdue,
        long checklistTotal, long checklistDone, boolean canEdit, long version, Instant createdAt, Instant updatedAt) {}
