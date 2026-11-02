package com.openstudy.modules.task.dto.response;

import java.time.Instant;

/**
 * Contract schema {@code TaskCard}, built for one reader at the moment of reading. When {@code accessible} is false every
 * other field is null, the task ID included, so a card says nothing about a task its reader may not open (M7-D07).
 */
public record TaskCardView(boolean accessible, String taskId, String branchId, String title, String status, String priority,
        ActorSummary assignee, Instant dueAt) {
    public static TaskCardView inaccessible() { return new TaskCardView(false, null, null, null, null, null, null, null); }
}
