package com.openstudy.modules.task.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.task.entity.TaskPriority;
import com.openstudy.modules.task.entity.TaskStatus;

/** Wire labels and field rules of the task contract. */
public final class TaskInputs {
    static final int MAX_CHECKLIST = 100;
    private static final Map<String, TaskStatus> STATUSES =
            Map.of("할 일", TaskStatus.TODO, "진행 중", TaskStatus.IN_PROGRESS, "완료", TaskStatus.DONE);
    private static final Map<String, TaskPriority> PRIORITIES = Map.of("낮음", TaskPriority.LOW,
            "보통", TaskPriority.NORMAL, "높음", TaskPriority.HIGH, "긴급", TaskPriority.URGENT);

    private TaskInputs() {}

    static TaskStatus status(String value, String field) {
        var status = STATUSES.get(value);
        if (status == null) throw ApiException.validation(field, "허용되지 않은 값입니다.");
        return status;
    }

    static TaskPriority priority(String value, String field) {
        var priority = PRIORITIES.get(value);
        if (priority == null) throw ApiException.validation(field, "허용되지 않은 값입니다.");
        return priority;
    }

    static String label(TaskStatus status) {
        return switch (status) {
            case TODO -> "할 일";
            case IN_PROGRESS -> "진행 중";
            case DONE -> "완료";
        };
    }

    static String label(TaskPriority priority) {
        return switch (priority) {
            case LOW -> "낮음";
            case NORMAL -> "보통";
            case HIGH -> "높음";
            case URGENT -> "긴급";
        };
    }

    /** Optional free text: null and blank both mean "no content". */
    static String body(Map<String, Object> values) {
        Object value = values.get("body");
        if (value == null) return null;
        if (!(value instanceof String text) || text.length() > 20000) throw ApiException.validation("body", "올바른 값을 입력해 주세요.");
        return text.isBlank() ? null : text.strip();
    }

    static Instant dueAt(Map<String, Object> values) {
        Object value = values.get("dueAt");
        if (value == null) return null;
        if (!(value instanceof String text)) throw ApiException.validation("dueAt", "올바른 시각을 입력해 주세요.");
        return instant(text, "dueAt");
    }

    /** Cut to the column's microseconds, so an unchanged value compares equal to the stored one. */
    static Instant instant(String value, String field) {
        try {
            return Instant.parse(value).truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException exception) {
            throw ApiException.validation(field, "올바른 시각을 입력해 주세요.");
        }
    }

    static List<String> checklist(StrictInput input) {
        Object value = input.values().get("checklist");
        if (value == null) return List.of();
        if (!(value instanceof List<?> rows) || rows.size() > MAX_CHECKLIST)
            throw ApiException.validation("checklist", "체크 항목은 100개까지 입력할 수 있습니다.");
        List<String> result = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof String text) || text.isBlank() || text.length() > 500)
                throw ApiException.validation("checklist", "체크 항목은 1~500자여야 합니다.");
            result.add(text.strip());
        }
        return result;
    }

    static long id(String value, String field) {
        try {
            if (!value.matches("[1-9][0-9]*")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw ApiException.validation(field, "올바른 ID를 입력해 주세요.");
        }
    }

    static boolean flag(String value, String field) {
        if (value == null || "false".equals(value)) return false;
        if ("true".equals(value)) return true;
        throw ApiException.validation(field, "true 또는 false를 입력해 주세요.");
    }

    public static long version(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,15}")) throw ApiException.validation("version", "올바른 버전을 입력해 주세요.");
        return Long.parseLong(value);
    }
}
