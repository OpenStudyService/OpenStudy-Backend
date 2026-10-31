package com.openstudy.modules.task.service;

import java.time.Instant;

import com.openstudy.modules.task.entity.TaskStatus;

/** Validated list and summary conditions. Shared so the two cannot accept different filters. */
public record TaskFilters(TaskStatus status, Long assignee, boolean mine, Instant dueFrom, Instant dueTo, boolean overdue) {
    public static TaskFilters parse(String status, String assigneeId, String mine, String dueFrom, String dueTo, String overdue) {
        return new TaskFilters(status == null ? null : TaskInputs.status(status, "status"),
                assigneeId == null ? null : TaskInputs.id(assigneeId, "assigneeId"), TaskInputs.flag(mine, "mine"),
                dueFrom == null ? null : TaskInputs.instant(dueFrom, "dueFrom"),
                dueTo == null ? null : TaskInputs.instant(dueTo, "dueTo"), TaskInputs.flag(overdue, "overdue"));
    }
}
