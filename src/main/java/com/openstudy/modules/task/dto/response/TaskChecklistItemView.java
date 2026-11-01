package com.openstudy.modules.task.dto.response;

import java.time.Instant;

/** Contract schema {@code TaskChecklistItem}. */
public record TaskChecklistItemView(String id, String body, boolean done, int sortOrder, ActorSummary updatedBy,
        Instant updatedAt) {}
