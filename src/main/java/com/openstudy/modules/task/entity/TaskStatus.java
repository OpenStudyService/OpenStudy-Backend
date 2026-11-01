package com.openstudy.modules.task.entity;

/** Allowed moves (FR-TASK-04). Reopening goes back to TODO, as the task screen's "다시 열기" does. */
public enum TaskStatus {
    TODO, IN_PROGRESS, DONE;

    public boolean canMoveTo(TaskStatus next) {
        return switch (this) {
            case TODO -> next == IN_PROGRESS || next == DONE;
            case IN_PROGRESS -> next == TODO || next == DONE;
            case DONE -> next == TODO;
        };
    }
}
