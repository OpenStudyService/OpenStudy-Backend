package com.openstudy.modules.task.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.idempotency.IdempotencyStore;
import com.openstudy.common.outbox.WorkspaceOutbox;
import com.openstudy.common.web.ApiVersion;
import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.common.web.StrictInput;
import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.authorization.service.AuthorizedCommands;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.task.dto.response.ActorSummary;
import com.openstudy.modules.task.dto.response.AssigneeCandidates;
import com.openstudy.modules.task.dto.response.TaskChecklistItemView;
import com.openstudy.modules.task.dto.response.TaskDetail;
import com.openstudy.modules.task.dto.response.TaskEventView;
import com.openstudy.modules.task.dto.response.TaskListItem;
import com.openstudy.modules.task.dto.response.TaskSummary;
import com.openstudy.modules.task.entity.TaskChecklistItem;
import com.openstudy.modules.task.entity.TaskEvent;
import com.openstudy.modules.task.entity.TaskPriority;
import com.openstudy.modules.task.entity.TaskStatus;
import com.openstudy.modules.task.entity.WorkTask;
import com.openstudy.modules.task.repository.TaskChecklistItemRepository;
import com.openstudy.modules.task.repository.TaskEventRepository;
import com.openstudy.modules.task.repository.TaskSearchRepository;
import com.openstudy.modules.task.repository.WorkTaskRepository;

/**
 * Branch tasks (FR-TASK-01~06·08, ADR-M7-001 M7-D01). Any valid member reads and creates; content, status and checklist
 * changes belong to the creator, the assignee or the branch's MANAGER, and a revoked membership outranks the first two.
 * Each change writes one task_events row per changed group, in the same transaction.
 */
@Service
@RequiredArgsConstructor
public class TaskService {
    private static final String CREATE = "tasks:create";
    private static final Set<String> IMMUTABLE = Set.of("branchId", "status", "checklist");
    private final WorkTaskRepository repository;
    private final TaskChecklistItemRepository checklist;
    private final TaskEventRepository events;
    private final TaskSearchRepository search;
    private final EmployeeAccountService employees;
    private final AuthorizationService policy;
    private final AuthorizedCommands commands;
    private final ObjectMapper json;
    private final WorkspaceOutbox outbox;
    private final IdempotencyStore requests;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<TaskListItem> list(long actor, String scope, ListQuery query, TaskFilters filters) {
        var state = policy.current(actor);
        Instant now = clock.instant();
        var sort = switch (query.sort()) {
            case "dueAt" -> TaskSearchRepository.Sort.DUE_AT;
            case "priority" -> TaskSearchRepository.Sort.PRIORITY;
            case "updatedAt" -> TaskSearchRepository.Sort.UPDATED_AT;
            default -> TaskSearchRepository.Sort.CREATED_AT;
        };
        var page = search.find(filter(policy.scope(state, scope, BranchAction.READ), query.q(), filters, actor, now), sort,
                query.direction() == ListQuery.Direction.DESC, query.offset(), query.pageSize());
        return new PageResponse<>(items(page.rows(), state, now), new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    @Transactional(readOnly = true)
    public TaskSummary summary(long actor, String scope, String q, TaskFilters filters) {
        var state = policy.current(actor);
        Instant now = clock.instant();
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        // ListQuery applies the same trim and length rule to q as the list.
        String text = ListQuery.parse(1, 1, q, null, null, Set.of("id"), "id").q();
        Map<Long, TaskSearchRepository.BranchCounts> found = new HashMap<>();
        search.counts(filter(selected, text, filters, actor, now)).forEach(row -> found.put(row.branchId(), row));
        var branches = new ArrayList<TaskSummary.BranchCounts>();
        var totals = new long[5];
        for (long branch : new TreeSet<>(selected)) {
            var row = found.getOrDefault(branch, new TaskSearchRepository.BranchCounts(branch, 0, 0, 0, 0, 0));
            branches.add(new TaskSummary.BranchCounts(Long.toString(branch), row.total(), row.todo(), row.inProgress(), row.done(), row.overdue()));
            totals[0] += row.total(); totals[1] += row.todo(); totals[2] += row.inProgress(); totals[3] += row.done(); totals[4] += row.overdue();
        }
        return new TaskSummary(now, new TaskSummary.Counts(totals[0], totals[1], totals[2], totals[3], totals[4]), branches);
    }

    @Transactional(readOnly = true)
    public TaskDetail get(long actor, long id, String scope) {
        var state = policy.current(actor);
        return detail(visible(state, id, scope), state);
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskEventView> history(long actor, long id, String scope, ListQuery query) {
        visible(policy.current(actor), id, scope);
        var rows = search.events(id, query.offset(), query.pageSize());
        var names = employees.names(rows.stream().map(TaskEvent::getActorEmployeeId).filter(Objects::nonNull).toList());
        return new PageResponse<>(rows.stream().map(row -> event(row, names)).toList(),
                new PageResponse.Meta(query.page(), query.pageSize(), events.countByTaskId(id)));
    }

    @Transactional(readOnly = true)
    public AssigneeCandidates candidates(long actor, String branchId) {
        if (branchId == null) throw ApiException.validation("branchId", "지점을 선택해 주세요.");
        long branch = TaskInputs.id(branchId, "branchId");
        policy.require(policy.current(actor), Set.of(branch), BranchAction.READ);
        return new AssigneeCandidates(employees.assignees(branch).stream()
                .map(employee -> new ActorSummary(employee.id(), employee.name())).toList());
    }

    public TaskDetail create(long actor, String scope, String key, Map<String, Object> body) {
        var input = new StrictInput(body).only("branchId", "title", "body", "assigneeId", "dueAt", "priority", "category", "status", "checklist");
        long branch = input.id("branchId");
        String title = input.text("title", 200);
        String content = TaskInputs.body(body);
        long assignee = input.id("assigneeId");
        Instant due = TaskInputs.dueAt(body);
        TaskPriority priority = input.has("priority") ? TaskInputs.priority(input.text("priority", 10), "priority") : TaskPriority.NORMAL;
        String category = input.text("category", 60);
        TaskStatus status = input.has("status") ? TaskInputs.status(input.text("status", 10), "status") : TaskStatus.TODO;
        List<String> items = TaskInputs.checklist(input);
        validKey(key);
        // scope only has to be well-formed and readable; the target is always branchId.
        policy.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        byte[] fingerprint = digest(json.writeValueAsString(Arrays.asList(branch, title, content, assignee,
                due == null ? null : due.toString(), priority.name(), category, status.name(), items)));
        return commands.inBranches(actor, Set.of(branch), BranchAction.READ, () -> {}, state -> {
            // Authorization has just been re-evaluated under the guard; only now may a stored result be replayed.
            var prior = requests.lock(actor, CREATE, scopeKey, key);
            Instant now = clock.instant();
            if (prior.isPresent()) {
                if (now.isBefore(prior.get().getExpiresAt())) {
                    if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash()))
                        throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                    return json.readValue(prior.get().getResponseJson(), TaskDetail.class);
                }
                requests.discard(prior.get());
            }
            requireAssignable(assignee, branch);
            var task = repository.saveAndFlush(new WorkTask(branch, actor, title, content, assignee, category, priority, status, due, now));
            for (int order = 0; order < items.size(); order++)
                checklist.save(new TaskChecklistItem(task.getId(), items.get(order), order, actor));
            record(task.getId(), actor, "CREATED", null, values("title", title, "assigneeId", Long.toString(assignee),
                    "status", TaskInputs.label(status)));
            outbox.invalidate(branch, "tasks", task.getId());
            var result = detail(task, state);
            requests.complete(actor, CREATE, scopeKey, key, fingerprint, 201, json.writeValueAsString(result), now.plus(Duration.ofHours(24)));
            return result;
        });
    }

    public TaskDetail update(long actor, long id, String scope, Map<String, Object> body) {
        if (body != null && body.keySet().stream().anyMatch(IMMUTABLE::contains))
            throw ApiException.unprocessable("IMMUTABLE_FIELD", "지점·상태·체크리스트는 이 요청으로 바꿀 수 없습니다.");
        var input = new StrictInput(body).only("version", "title", "body", "assigneeId", "dueAt", "priority", "category");
        long version = input.version("version");
        String title = input.has("title") ? input.text("title", 200) : null;
        String content = TaskInputs.body(body);
        Long assignee = input.has("assigneeId") ? Long.valueOf(input.id("assigneeId")) : null;
        Instant due = TaskInputs.dueAt(body);
        TaskPriority priority = input.has("priority") ? TaskInputs.priority(input.text("priority", 10), "priority") : null;
        String category = input.has("category") ? input.text("category", 60) : null;
        return edit(actor, id, scope, version, (state, task) -> {
            String newTitle = title == null ? task.getTitle() : title;
            String newBody = input.has("body") ? content : task.getBody();
            String newCategory = category == null ? task.getCategory() : category;
            TaskPriority newPriority = priority == null ? task.getPriority() : priority;
            long newAssignee = assignee == null ? task.getAssigneeEmployeeId() : assignee;
            Instant newDue = input.has("dueAt") ? due : task.getDueAt();
            Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
            compare("title", task.getTitle(), newTitle, before, after);
            compare("body", task.getBody(), newBody, before, after);
            compare("category", task.getCategory(), newCategory, before, after);
            compare("priority", TaskInputs.label(task.getPriority()), TaskInputs.label(newPriority), before, after);
            boolean reassigned = newAssignee != task.getAssigneeEmployeeId();
            boolean rescheduled = !Objects.equals(newDue, task.getDueAt());
            // An identical request is not a change: no version bump, history row or event.
            if (before.isEmpty() && !reassigned && !rescheduled) return detail(task, state);
            if (reassigned) requireAssignable(newAssignee, task.getBranchId());
            if (!before.isEmpty()) record(id, actor, "CONTENT_CHANGED", before, after);
            if (reassigned) record(id, actor, "ASSIGNEE_CHANGED", values("assigneeId", Long.toString(task.getAssigneeEmployeeId())),
                    values("assigneeId", Long.toString(newAssignee)));
            if (rescheduled) record(id, actor, "DUE_CHANGED", values("dueAt", text(task.getDueAt())), values("dueAt", text(newDue)));
            task.changeContent(newTitle, newBody, newCategory, newPriority);
            task.assign(newAssignee);
            task.reschedule(newDue);
            return saved(task, state);
        });
    }

    public TaskDetail changeStatus(long actor, long id, String scope, Map<String, Object> body) {
        var input = new StrictInput(body).only("version", "status");
        long version = input.version("version");
        TaskStatus next = TaskInputs.status(input.text("status", 10), "status");
        return edit(actor, id, scope, version, (state, task) -> {
            TaskStatus previous = task.getStatus();
            if (previous == next) return detail(task, state);
            if (!previous.canMoveTo(next)) throw ApiException.conflict("INVALID_STATE", "이 상태로 바꿀 수 없습니다.");
            record(id, actor, "STATUS_CHANGED", values("status", TaskInputs.label(previous)), values("status", TaskInputs.label(next)));
            task.changeStatus(next, clock.instant());
            return saved(task, state);
        });
    }

    public TaskDetail addChecklistItem(long actor, long id, String scope, Map<String, Object> body) {
        var input = new StrictInput(body).only("version", "body");
        long version = input.version("version");
        String text = input.text("body", 500);
        return edit(actor, id, scope, version, (state, task) -> {
            if (checklist.countByTaskId(id) >= TaskInputs.MAX_CHECKLIST)
                throw ApiException.validation("body", "체크 항목은 100개까지 입력할 수 있습니다.");
            var item = checklist.save(new TaskChecklistItem(id, text, checklist.lastSortOrder(id) + 1, actor));
            record(id, actor, "CHECKLIST_ADDED", null, values("itemId", item.getId().toString(), "body", text));
            return advanced(id, state);
        });
    }

    public TaskDetail changeChecklistItem(long actor, long id, long item, String scope, Map<String, Object> body) {
        var input = new StrictInput(body).only("version", "body", "done");
        long version = input.version("version");
        String text = input.has("body") ? input.text("body", 500) : null;
        if (input.has("done") && !(body.get("done") instanceof Boolean)) throw ApiException.validation("done", "true 또는 false를 입력해 주세요.");
        Boolean done = (Boolean) body.get("done");
        return edit(actor, id, scope, version, (state, task) -> {
            var row = item(id, item);
            String newText = text == null ? row.getBody() : text;
            boolean newDone = done == null ? row.isDone() : done;
            Map<String, Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
            compare("body", row.getBody(), newText, before, after);
            compare("done", row.isDone(), newDone, before, after);
            if (before.isEmpty()) return detail(task, state);
            before.put("itemId", Long.toString(item));
            after.put("itemId", Long.toString(item));
            row.change(newText, newDone, actor);
            record(id, actor, "CHECKLIST_CHANGED", before, after);
            return advanced(id, state);
        });
    }

    public TaskDetail removeChecklistItem(long actor, long id, long item, String scope, long version) {
        return edit(actor, id, scope, version, (state, task) -> {
            var row = item(id, item);
            var before = new LinkedHashMap<String, Object>();
            before.put("itemId", Long.toString(item));
            before.put("body", row.getBody());
            before.put("done", row.isDone());
            checklist.delete(row);
            record(id, actor, "CHECKLIST_REMOVED", before, null);
            return advanced(id, state);
        });
    }

    /** Shared command frame: scope, parent lock, current editor check, then the version, in that order. */
    private TaskDetail edit(long actor, long id, String scope, long version, BiFunction<AuthorizationState, WorkTask, TaskDetail> change) {
        long branch = visible(policy.current(actor), id, scope).getBranchId();
        return commands.inBranches(actor, Set.of(branch), BranchAction.READ, () -> repository.lock(id), state -> {
            var task = repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            if (!editable(state, task)) throw AuthorizationService.forbidden();
            ApiVersion.requireCurrent(version, task.getVersion(), "/api/tasks/" + id);
            return change.apply(state, task);
        });
    }

    private TaskDetail saved(WorkTask task, AuthorizationState state) {
        var result = repository.saveAndFlush(task);
        outbox.invalidate(result.getBranchId(), "tasks", result.getId());
        return detail(result, state);
    }

    private TaskDetail advanced(long id, AuthorizationState state) {
        repository.incrementVersion(id);
        var task = repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        outbox.invalidate(task.getBranchId(), "tasks", id);
        return detail(task, state);
    }

    private TaskChecklistItem item(long task, long item) {
        return checklist.findById(item).filter(row -> row.getTaskId() == task)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** 404 hides tasks outside the reader's scope, so IDs are not an existence oracle. */
    private WorkTask visible(AuthorizationState state, long id, String scope) {
        Set<Long> selected = policy.scope(state, scope, BranchAction.READ);
        return repository.findById(id).filter(task -> selected.contains(task.getBranchId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private boolean editable(AuthorizationState state, WorkTask task) {
        return policy.permits(state, task.getBranchId(), BranchAction.READ)
                && (task.getCreatedBy() == state.employeeId() || task.getAssigneeEmployeeId() == state.employeeId()
                || policy.permits(state, task.getBranchId(), BranchAction.MANAGE_COLLABORATION));
    }

    private void requireAssignable(long employee, long branch) {
        if (!employees.assignable(employee, branch))
            throw ApiException.unprocessable("INVALID_ASSIGNEE", "해당 지점의 활성 직원만 담당자로 지정할 수 있습니다.");
    }

    private void record(long task, long actor, String type, Map<String, Object> before, Map<String, Object> after) {
        events.save(new TaskEvent(task, actor, type, before == null ? null : json.writeValueAsString(before),
                after == null ? null : json.writeValueAsString(after)));
    }

    private static void compare(String field, Object before, Object after, Map<String, Object> beforeValues, Map<String, Object> afterValues) {
        if (Objects.equals(before, after)) return;
        beforeValues.put(field, before);
        afterValues.put(field, after);
    }

    private static Map<String, Object> values(String... pairs) {
        var values = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) values.put(pairs[index], pairs[index + 1]);
        return values;
    }

    private static String text(Instant value) { return value == null ? null : value.toString(); }

    private TaskSearchRepository.Filter filter(Set<Long> selected, String q, TaskFilters filters, long actor, Instant now) {
        return new TaskSearchRepository.Filter(selected, q, filters.status(), filters.assignee(), filters.mine() ? actor : null,
                filters.dueFrom(), filters.dueTo(), filters.overdue(), now);
    }

    private List<TaskListItem> items(List<WorkTask> tasks, AuthorizationState state, Instant now) {
        if (tasks.isEmpty()) return List.of();
        Map<Long, long[]> progress = new HashMap<>();
        checklist.progress(tasks.stream().map(WorkTask::getId).toList()).forEach(row ->
                progress.put((Long) row[0], new long[] {((Number) row[1]).longValue(), ((Number) row[2]).longValue()}));
        var names = employees.names(tasks.stream().flatMap(task -> Stream.of(task.getAssigneeEmployeeId(), task.getCreatedBy())).toList());
        return tasks.stream().map(task -> {
            long[] counts = progress.getOrDefault(task.getId(), new long[2]);
            return new TaskListItem(task.getId().toString(), Long.toString(task.getBranchId()), task.getTitle(), task.getCategory(),
                    TaskInputs.label(task.getPriority()), TaskInputs.label(task.getStatus()), actor(task.getAssigneeEmployeeId(), names),
                    actor(task.getCreatedBy(), names), task.getDueAt(), task.getCompletedAt(), overdue(task, now), counts[0], counts[1],
                    editable(state, task), ApiVersion.fromDatabase(task.getVersion()), task.getCreatedAt(), task.getUpdatedAt());
        }).toList();
    }

    private TaskDetail detail(WorkTask task, AuthorizationState state) {
        var rows = checklist.findByTaskIdOrderBySortOrderAscIdAsc(task.getId());
        var ids = new ArrayList<>(List.of(task.getAssigneeEmployeeId(), task.getCreatedBy()));
        rows.stream().map(TaskChecklistItem::getUpdatedBy).filter(Objects::nonNull).forEach(ids::add);
        var names = employees.names(ids);
        var views = rows.stream().map(row -> new TaskChecklistItemView(row.getId().toString(), row.getBody(), row.isDone(), row.getSortOrder(),
                row.getUpdatedBy() == null ? null : actor(row.getUpdatedBy(), names), row.getUpdatedAt())).toList();
        return new TaskDetail(task.getId().toString(), Long.toString(task.getBranchId()), task.getTitle(), task.getCategory(),
                TaskInputs.label(task.getPriority()), TaskInputs.label(task.getStatus()), actor(task.getAssigneeEmployeeId(), names),
                actor(task.getCreatedBy(), names), task.getDueAt(), task.getCompletedAt(), overdue(task, clock.instant()), rows.size(),
                rows.stream().filter(TaskChecklistItem::isDone).count(), editable(state, task), ApiVersion.fromDatabase(task.getVersion()),
                task.getCreatedAt(), task.getUpdatedAt(), task.getBody(), views);
    }

    private static boolean overdue(WorkTask task, Instant now) {
        return task.getStatus() != TaskStatus.DONE && task.getDueAt() != null && task.getDueAt().isBefore(now);
    }

    private TaskEventView event(TaskEvent row, Map<Long, String> names) {
        return new TaskEventView(row.getId().toString(), row.getEventType(),
                row.getActorEmployeeId() == null ? null : actor(row.getActorEmployeeId(), names),
                map(row.getBeforeJson()), map(row.getAfterJson()), row.getCreatedAt());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String value) { return value == null ? null : json.readValue(value, Map.class); }

    private static ActorSummary actor(long id, Map<Long, String> names) {
        return new ActorSummary(Long.toString(id), names.getOrDefault(id, ""));
    }

    private static void validKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 128)
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
