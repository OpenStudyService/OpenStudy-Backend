package com.openstudy.modules.task.controller;

import java.net.URI;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.task.dto.response.AssigneeCandidates;
import com.openstudy.modules.task.dto.response.TaskDetail;
import com.openstudy.modules.task.dto.response.TaskEventView;
import com.openstudy.modules.task.dto.response.TaskListItem;
import com.openstudy.modules.task.dto.response.TaskSummary;
import com.openstudy.modules.task.service.TaskFilters;
import com.openstudy.modules.task.service.TaskInputs;
import com.openstudy.modules.task.service.TaskService;

/**
 * There is deliberately no DELETE for a task: only checklist items are removed (collaboration-api.md). Tasks and
 * assignee candidates are two top-level paths, so the class maps only /api.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TaskController {

    private static final Set<String> SORT_FIELDS = Set.of("dueAt", "priority", "createdAt", "updatedAt");
    private static final String DEFAULT_SORT = "createdAt";
    private static final Set<String> EVENT_SORT_FIELDS = Set.of("id");
    private static final String EVENT_DEFAULT_SORT = "id";

    private final TaskService taskService;

    @GetMapping("/tasks")
    public PageResponse<TaskListItem> listTasks(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String assigneeId,
            @RequestParam(required = false) String mine,
            @RequestParam(required = false) String dueFrom,
            @RequestParam(required = false) String dueTo,
            @RequestParam(required = false) String overdue,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction
    ) {
        long actorId = actorId(actor);
        ListQuery query = ListQuery.parse(page, pageSize, q, sort, direction, SORT_FIELDS, DEFAULT_SORT);
        TaskFilters filters = TaskFilters.parse(status, assigneeId, mine, dueFrom, dueTo, overdue);

        return taskService.list(actorId, scope, query, filters);
    }

    @GetMapping("/tasks/summary")
    public TaskSummary getTaskSummary(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String assigneeId,
            @RequestParam(required = false) String mine,
            @RequestParam(required = false) String dueFrom,
            @RequestParam(required = false) String dueTo,
            @RequestParam(required = false) String overdue
    ) {
        long actorId = actorId(actor);
        TaskFilters filters = TaskFilters.parse(null, assigneeId, mine, dueFrom, dueTo, overdue);

        return taskService.summary(actorId, scope, q, filters);
    }

    @GetMapping("/tasks/{id}")
    public TaskDetail getTask(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return taskService.get(actorId(actor), id, scope);
    }

    @PostMapping("/tasks")
    public ResponseEntity<TaskDetail> createTask(
            Authentication actor,
            @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> body
    ) {
        TaskDetail result = taskService.create(actorId(actor), scope, key, body);

        return ResponseEntity.created(URI.create("/api/tasks/" + result.id())).body(result);
    }

    @PatchMapping("/tasks/{id}")
    public TaskDetail updateTask(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body
    ) {
        return taskService.update(actorId(actor), id, scope, body);
    }

    @PostMapping("/tasks/{id}/status")
    public TaskDetail changeTaskStatus(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body
    ) {
        return taskService.changeStatus(actorId(actor), id, scope, body);
    }

    @GetMapping("/tasks/{id}/events")
    public PageResponse<TaskEventView> listTaskEvents(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        long actorId = actorId(actor);
        ListQuery query = ListQuery.parse(page, pageSize, null, null, null, EVENT_SORT_FIELDS, EVENT_DEFAULT_SORT);

        return taskService.history(actorId, id, scope, query);
    }

    @PostMapping("/tasks/{id}/checklist-items")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDetail addTaskChecklistItem(
            Authentication actor,
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body
    ) {
        return taskService.addChecklistItem(actorId(actor), id, scope, body);
    }

    @PatchMapping("/tasks/{id}/checklist-items/{itemId}")
    public TaskDetail updateTaskChecklistItem(
            Authentication actor,
            @PathVariable long id,
            @PathVariable long itemId,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body
    ) {
        return taskService.changeChecklistItem(actorId(actor), id, itemId, scope, body);
    }

    @DeleteMapping("/tasks/{id}/checklist-items/{itemId}")
    public TaskDetail deleteTaskChecklistItem(
            Authentication actor,
            @PathVariable long id,
            @PathVariable long itemId,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(required = false) String version
    ) {
        long actorId = actorId(actor);
        long parsedVersion = TaskInputs.parseVersion(version);

        return taskService.removeChecklistItem(actorId, id, itemId, scope, parsedVersion);
    }

    @GetMapping("/assignee-candidates")
    public AssigneeCandidates listAssigneeCandidates(
            Authentication actor,
            @RequestParam(required = false) String branchId
    ) {
        return taskService.candidates(actorId(actor), branchId);
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
