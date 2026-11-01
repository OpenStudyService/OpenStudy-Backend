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

/** There is deliberately no DELETE for a task: only checklist items are removed (collaboration-api.md). */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TaskController {
    private static final Set<String> SORTS = Set.of("dueAt", "priority", "createdAt", "updatedAt");
    private final TaskService service;

    @GetMapping("/tasks")
    public PageResponse<TaskListItem> list(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q, @RequestParam(required = false) String status,
            @RequestParam(required = false) String assigneeId, @RequestParam(required = false) String mine,
            @RequestParam(required = false) String dueFrom, @RequestParam(required = false) String dueTo,
            @RequestParam(required = false) String overdue, @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        return service.list(actor(actor), scope, ListQuery.parse(page, pageSize, q, sort, direction, SORTS, "createdAt"),
                TaskFilters.parse(status, assigneeId, mine, dueFrom, dueTo, overdue));
    }

    @GetMapping("/tasks/summary")
    public TaskSummary summary(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestParam(required = false) String q, @RequestParam(required = false) String assigneeId,
            @RequestParam(required = false) String mine, @RequestParam(required = false) String dueFrom,
            @RequestParam(required = false) String dueTo, @RequestParam(required = false) String overdue) {
        return service.summary(actor(actor), scope, q, TaskFilters.parse(null, assigneeId, mine, dueFrom, dueTo, overdue));
    }

    @GetMapping("/tasks/{id}")
    public TaskDetail get(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope) {
        return service.get(actor(actor), id, scope);
    }

    @PostMapping("/tasks")
    public ResponseEntity<TaskDetail> create(Authentication actor, @RequestParam(defaultValue = "all") String scope,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody Map<String, Object> body) {
        var result = service.create(actor(actor), scope, key, body);
        return ResponseEntity.created(URI.create("/api/tasks/" + result.id())).body(result);
    }

    @PatchMapping("/tasks/{id}")
    public TaskDetail update(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body) {
        return service.update(actor(actor), id, scope, body);
    }

    @PostMapping("/tasks/{id}/status")
    public TaskDetail status(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body) {
        return service.changeStatus(actor(actor), id, scope, body);
    }

    @GetMapping("/tasks/{id}/events")
    public PageResponse<TaskEventView> events(Authentication actor, @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope, @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return service.history(actor(actor), id, scope, ListQuery.parse(page, pageSize, null, null, null, Set.of("id"), "id"));
    }

    @PostMapping("/tasks/{id}/checklist-items")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDetail addItem(Authentication actor, @PathVariable long id, @RequestParam(defaultValue = "all") String scope,
            @RequestBody Map<String, Object> body) {
        return service.addChecklistItem(actor(actor), id, scope, body);
    }

    @PatchMapping("/tasks/{id}/checklist-items/{itemId}")
    public TaskDetail updateItem(Authentication actor, @PathVariable long id, @PathVariable long itemId,
            @RequestParam(defaultValue = "all") String scope, @RequestBody Map<String, Object> body) {
        return service.changeChecklistItem(actor(actor), id, itemId, scope, body);
    }

    @DeleteMapping("/tasks/{id}/checklist-items/{itemId}")
    public TaskDetail removeItem(Authentication actor, @PathVariable long id, @PathVariable long itemId,
            @RequestParam(defaultValue = "all") String scope, @RequestParam(required = false) String version) {
        return service.removeChecklistItem(actor(actor), id, itemId, scope, TaskInputs.version(version));
    }

    @GetMapping("/assignee-candidates")
    public AssigneeCandidates candidates(Authentication actor, @RequestParam(required = false) String branchId) {
        return service.candidates(actor(actor), branchId);
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
