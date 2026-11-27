package com.openstudy.modules.task.service;

import java.time.Instant;

import com.openstudy.modules.task.entity.TaskStatus;

/** Validated list and summary conditions. Shared so the two cannot accept different filters. */
public record TaskFilters(
        TaskStatus status,
        Long assignee,
        boolean mine,
        Instant dueFrom,
        Instant dueTo,
        boolean overdue
) {

    public static TaskFilters parse(
            String status,
            String assigneeId,
            String mine,
            String dueFrom,
            String dueTo,
            String overdue
    ) {
        TaskStatus parsedStatus = status == null ? null : TaskInputs.parseStatus(status, "status");
        Long assignee = assigneeId == null ? null : TaskInputs.parseId(assigneeId, "assigneeId");
        boolean onlyMine = TaskInputs.parseFlag(mine, "mine");
        Instant from = dueFrom == null ? null : TaskInputs.parseInstant(dueFrom, "dueFrom");
        Instant to = dueTo == null ? null : TaskInputs.parseInstant(dueTo, "dueTo");
        boolean onlyOverdue = TaskInputs.parseFlag(overdue, "overdue");

        return new TaskFilters(parsedStatus, assignee, onlyMine, from, to, onlyOverdue);
    }
}
