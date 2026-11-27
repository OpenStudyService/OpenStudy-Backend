package com.openstudy.modules.task.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.util.Optional;
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
import com.openstudy.common.idempotency.IdempotencyRecord;
import com.openstudy.common.idempotency.IdempotencyStore;
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
    private static final Set<String> SUMMARY_SORT_FIELDS = Set.of("id");
    private static final String SUMMARY_DEFAULT_SORT = "id";
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_LABEL_LENGTH = 10;
    private static final int MAX_CATEGORY_LENGTH = 60;
    private static final int MAX_ITEM_LENGTH = 500;
    private static final int MIN_UPDATE_FIELDS = 2;
    private static final int MIN_KEY_LENGTH = 16;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int CREATED = 201;
    private static final Duration RESULT_RETENTION = Duration.ofHours(24);
    private static final String HASH_ALGORITHM = "SHA-256";

    private final WorkTaskRepository workTaskRepository;
    private final TaskChecklistItemRepository taskChecklistItemRepository;
    private final TaskEventRepository taskEventRepository;
    private final TaskSearchRepository taskSearchRepository;
    private final EmployeeAccountService employeeAccountService;
    private final AuthorizationService authorizationService;
    private final AuthorizedCommands authorizedCommands;
    private final ObjectMapper objectMapper;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    /** Item total and finished items of one task's checklist. */
    private record ChecklistProgress(long total, long done) {

        static final ChecklistProgress NONE = new ChecklistProgress(0, 0);
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskListItem> list(long actor, String scope, ListQuery query, TaskFilters filters) {
        AuthorizationState state = authorizationService.current(actor);
        Instant now = clock.instant();
        TaskSearchRepository.Sort sort = switch (query.sort()) {
            case "dueAt" -> TaskSearchRepository.Sort.DUE_AT;
            case "priority" -> TaskSearchRepository.Sort.PRIORITY;
            case "updatedAt" -> TaskSearchRepository.Sort.UPDATED_AT;
            default -> TaskSearchRepository.Sort.CREATED_AT;
        };
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);
        TaskSearchRepository.Filter filter = toSearchFilter(selected, query.q(), filters, actor, now);
        boolean descending = query.direction() == ListQuery.Direction.DESC;
        TaskSearchRepository.Page page = taskSearchRepository.find(
                filter,
                sort,
                descending,
                query.offset(),
                query.pageSize()
        );
        List<TaskListItem> items = toListItems(page.rows(), state, now);

        return new PageResponse<>(items, new PageResponse.Meta(query.page(), query.pageSize(), page.total()));
    }

    @Transactional(readOnly = true)
    public TaskSummary summary(long actor, String scope, String q, TaskFilters filters) {
        AuthorizationState state = authorizationService.current(actor);
        Instant now = clock.instant();
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);
        // ListQuery applies the same trim and length rule to q as the list.
        String text = ListQuery.parse(1, 1, q, null, null, SUMMARY_SORT_FIELDS, SUMMARY_DEFAULT_SORT).q();
        Map<Long, TaskSearchRepository.BranchCounts> found = new HashMap<>();
        taskSearchRepository.countByBranch(toSearchFilter(selected, text, filters, actor, now))
                .forEach(row -> found.put(row.branchId(), row));
        List<TaskSummary.BranchCounts> branches = new ArrayList<>();
        long total = 0;
        long todo = 0;
        long inProgress = 0;
        long done = 0;
        long overdue = 0;

        for (long branch : new TreeSet<>(selected)) {
            TaskSearchRepository.BranchCounts empty = new TaskSearchRepository.BranchCounts(branch, 0, 0, 0, 0, 0);
            TaskSearchRepository.BranchCounts row = found.getOrDefault(branch, empty);
            branches.add(new TaskSummary.BranchCounts(
                    Long.toString(branch),
                    row.total(),
                    row.todo(),
                    row.inProgress(),
                    row.done(),
                    row.overdue()
            ));
            total += row.total();
            todo += row.todo();
            inProgress += row.inProgress();
            done += row.done();
            overdue += row.overdue();
        }

        TaskSummary.Counts totals = new TaskSummary.Counts(total, todo, inProgress, done, overdue);

        return new TaskSummary(now, totals, branches);
    }

    /**
     * Tasks of the same scope and filters completed in [from, to): the home's "오늘 완료한 업무" (M9-D08). The list has no
     * completion time filter, so the home source names this as a client filter on {@code status=완료}.
     */
    @Transactional(readOnly = true)
    public long completedBetween(long actor, String scope, TaskFilters filters, Instant from, Instant to) {
        AuthorizationState state = authorizationService.current(actor);
        Instant now = clock.instant();
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);

        return taskSearchRepository.countCompleted(toSearchFilter(selected, "", filters, actor, now), from, to);
    }

    @Transactional(readOnly = true)
    public TaskDetail get(long actor, long id, String scope) {
        AuthorizationState state = authorizationService.current(actor);
        WorkTask task = findVisibleTask(state, id, scope);

        return toDetail(task, state);
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskEventView> history(long actor, long id, String scope, ListQuery query) {
        findVisibleTask(authorizationService.current(actor), id, scope);
        List<TaskEvent> rows = taskSearchRepository.findEvents(id, query.offset(), query.pageSize());
        List<Long> actorIds = rows.stream()
                .map(TaskEvent::getActorEmployeeId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, String> names = employeeAccountService.names(actorIds);
        List<TaskEventView> items = rows.stream()
                .map(row -> toEventView(row, names))
                .toList();

        return new PageResponse<>(items, new PageResponse.Meta(query.page(), query.pageSize(),
                taskEventRepository.countByTaskId(id)));
    }

    @Transactional(readOnly = true)
    public AssigneeCandidates candidates(long actor, String branchId) {
        if (branchId == null) {
            throw ApiException.validation("branchId", "지점을 선택해 주세요.");
        }

        long branch = TaskInputs.parseId(branchId, "branchId");
        authorizationService.require(authorizationService.current(actor), Set.of(branch), BranchAction.READ);
        List<ActorSummary> assignees = employeeAccountService.assignees(branch)
                .stream()
                .map(employee -> new ActorSummary(employee.id(), employee.name()))
                .toList();

        return new AssigneeCandidates(assignees);
    }

    public TaskDetail create(long actor, String scope, String key, Map<String, Object> body) {
        StrictInput input = new StrictInput(body).only(
                "branchId",
                "title",
                "body",
                "assigneeId",
                "dueAt",
                "priority",
                "category",
                "status",
                "checklist"
        );
        long branch = input.id("branchId");
        String title = input.text("title", MAX_TITLE_LENGTH);
        String content = TaskInputs.parseBody(body);
        long assignee = input.id("assigneeId");
        Instant due = TaskInputs.parseDueAt(body);
        TaskPriority priority = input.has("priority")
                ? TaskInputs.parsePriority(input.text("priority", MAX_LABEL_LENGTH), "priority")
                : TaskPriority.NORMAL;
        String category = input.text("category", MAX_CATEGORY_LENGTH);
        TaskStatus status = input.has("status")
                ? TaskInputs.parseStatus(input.text("status", MAX_LABEL_LENGTH), "status")
                : TaskStatus.TODO;
        List<String> items = TaskInputs.parseChecklist(input);
        requireValidKey(key);
        // scope only has to be well-formed and readable; the target is always branchId.
        authorizationService.scope(actor, scope, BranchAction.READ);
        String scopeKey = Long.toString(branch);
        List<Object> fingerprintInput = Arrays.asList(
                branch,
                title,
                content,
                assignee,
                due == null ? null : due.toString(),
                priority.name(),
                category,
                status.name(),
                items
        );
        byte[] fingerprint = digest(objectMapper.writeValueAsString(fingerprintInput));

        return authorizedCommands.inBranches(actor, Set.of(branch), BranchAction.READ, () -> {}, state -> {
            // Authorization has just been re-evaluated under the guard; only now may a stored result be replayed.
            Optional<IdempotencyRecord> prior = idempotencyStore.lock(actor, CREATE, scopeKey, key);
            Instant now = clock.instant();

            if (prior.isPresent()) {
                if (now.isBefore(prior.get().getExpiresAt())) {
                    if (!MessageDigest.isEqual(fingerprint, prior.get().getRequestHash())) {
                        throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "같은 키로 다른 입력을 저장할 수 없습니다.");
                    }

                    return objectMapper.readValue(prior.get().getResponseJson(), TaskDetail.class);
                }

                idempotencyStore.discard(prior.get());
            }

            requireAssignable(assignee, branch);
            WorkTask newTask = new WorkTask(branch, actor, title, content, assignee, category, priority, status, due,
                    now);
            WorkTask task = workTaskRepository.saveAndFlush(newTask);

            for (int order = 0; order < items.size(); order++) {
                taskChecklistItemRepository.save(new TaskChecklistItem(task.getId(), items.get(order), order, actor));
            }

            Map<String, Object> after = toValues(
                    "title", title,
                    "assigneeId", Long.toString(assignee),
                    "status", TaskInputs.toLabel(status)
            );
            recordEvent(task.getId(), actor, "CREATED", null, after);
            TaskDetail result = toDetail(task, state);
            String responseJson = objectMapper.writeValueAsString(result);
            Instant expiresAt = now.plus(RESULT_RETENTION);
            idempotencyStore.complete(actor, CREATE, scopeKey, key, fingerprint, CREATED, responseJson, expiresAt);

            return result;
        });
    }

    public TaskDetail update(long actor, long id, String scope, Map<String, Object> body) {
        boolean hasImmutableField = body != null && body.keySet().stream().anyMatch(IMMUTABLE::contains);

        if (hasImmutableField) {
            throw ApiException.unprocessable("IMMUTABLE_FIELD", "지점·상태·체크리스트는 이 요청으로 바꿀 수 없습니다.");
        }

        StrictInput input = new StrictInput(body)
                .only("version", "title", "body", "assigneeId", "dueAt", "priority", "category");

        if (body.size() < MIN_UPDATE_FIELDS) {
            throw ApiException.validation("body", "변경할 항목이 필요합니다.");
        }

        long version = input.version("version");
        String title = input.has("title") ? input.text("title", MAX_TITLE_LENGTH) : null;
        String content = TaskInputs.parseBody(body);
        Long assignee = input.has("assigneeId") ? Long.valueOf(input.id("assigneeId")) : null;
        Instant due = TaskInputs.parseDueAt(body);
        TaskPriority priority = input.has("priority")
                ? TaskInputs.parsePriority(input.text("priority", MAX_LABEL_LENGTH), "priority")
                : null;
        String category = input.has("category") ? input.text("category", MAX_CATEGORY_LENGTH) : null;

        return applyEdit(actor, id, scope, version, (state, task) -> {
            String newTitle = title == null ? task.getTitle() : title;
            String newBody = input.has("body") ? content : task.getBody();
            String newCategory = category == null ? task.getCategory() : category;
            TaskPriority newPriority = priority == null ? task.getPriority() : priority;
            long newAssignee = assignee == null ? task.getAssigneeEmployeeId() : assignee;
            Instant newDue = input.has("dueAt") ? due : task.getDueAt();
            Map<String, Object> before = new LinkedHashMap<>();
            Map<String, Object> after = new LinkedHashMap<>();
            putIfChanged("title", task.getTitle(), newTitle, before, after);
            putIfChanged("body", task.getBody(), newBody, before, after);
            putIfChanged("category", task.getCategory(), newCategory, before, after);
            putIfChanged("priority", TaskInputs.toLabel(task.getPriority()), TaskInputs.toLabel(newPriority), before,
                    after);
            boolean reassigned = newAssignee != task.getAssigneeEmployeeId();
            boolean rescheduled = !Objects.equals(newDue, task.getDueAt());

            // An identical request is not a change: no version bump, history row or event.
            if (before.isEmpty() && !reassigned && !rescheduled) {
                return toDetail(task, state);
            }

            if (reassigned) {
                requireAssignable(newAssignee, task.getBranchId());
            }

            if (!before.isEmpty()) {
                recordEvent(id, actor, "CONTENT_CHANGED", before, after);
            }

            if (reassigned) {
                recordEvent(id, actor, "ASSIGNEE_CHANGED",
                        toValues("assigneeId", Long.toString(task.getAssigneeEmployeeId())),
                        toValues("assigneeId", Long.toString(newAssignee)));
            }

            if (rescheduled) {
                recordEvent(id, actor, "DUE_CHANGED",
                        toValues("dueAt", toText(task.getDueAt())),
                        toValues("dueAt", toText(newDue)));
            }

            task.changeContent(newTitle, newBody, newCategory, newPriority);
            task.assign(newAssignee);
            task.reschedule(newDue);

            return saveAndView(task, state);
        });
    }

    public TaskDetail changeStatus(long actor, long id, String scope, Map<String, Object> body) {
        StrictInput input = new StrictInput(body).only("version", "status");
        long version = input.version("version");
        TaskStatus next = TaskInputs.parseStatus(input.text("status", MAX_LABEL_LENGTH), "status");

        return applyEdit(actor, id, scope, version, (state, task) -> {
            TaskStatus previous = task.getStatus();

            if (previous == next) {
                return toDetail(task, state);
            }

            if (!previous.canMoveTo(next)) {
                throw ApiException.conflict("INVALID_STATE", "이 상태로 바꿀 수 없습니다.");
            }

            recordEvent(id, actor, "STATUS_CHANGED",
                    toValues("status", TaskInputs.toLabel(previous)),
                    toValues("status", TaskInputs.toLabel(next)));
            task.changeStatus(next, clock.instant());

            return saveAndView(task, state);
        });
    }

    public TaskDetail addChecklistItem(long actor, long id, String scope, Map<String, Object> body) {
        StrictInput input = new StrictInput(body).only("version", "body");
        long version = input.version("version");
        String text = input.text("body", MAX_ITEM_LENGTH);

        return applyEdit(actor, id, scope, version, (state, task) -> {
            if (taskChecklistItemRepository.countByTaskId(id) >= TaskInputs.MAX_CHECKLIST) {
                throw ApiException.validation("body", "체크 항목은 100개까지 입력할 수 있습니다.");
            }

            int sortOrder = taskChecklistItemRepository.findLastSortOrder(id) + 1;
            TaskChecklistItem newItem = new TaskChecklistItem(id, text, sortOrder, actor);
            TaskChecklistItem item = taskChecklistItemRepository.save(newItem);
            recordEvent(id, actor, "CHECKLIST_ADDED", null, toValues("itemId", item.getId().toString(), "body", text));

            return advanceVersion(id, state);
        });
    }

    public TaskDetail changeChecklistItem(long actor, long id, long item, String scope, Map<String, Object> body) {
        StrictInput input = new StrictInput(body).only("version", "body", "done");
        long version = input.version("version");
        String text = input.has("body") ? input.text("body", MAX_ITEM_LENGTH) : null;

        if (input.has("done") && !(body.get("done") instanceof Boolean)) {
            throw ApiException.validation("done", "true 또는 false를 입력해 주세요.");
        }

        Boolean done = (Boolean) body.get("done");

        return applyEdit(actor, id, scope, version, (state, task) -> {
            TaskChecklistItem row = findItem(id, item);
            String newText = text == null ? row.getBody() : text;
            boolean newDone = done == null ? row.isDone() : done;
            Map<String, Object> before = new LinkedHashMap<>();
            Map<String, Object> after = new LinkedHashMap<>();
            putIfChanged("body", row.getBody(), newText, before, after);
            putIfChanged("done", row.isDone(), newDone, before, after);

            if (before.isEmpty()) {
                return toDetail(task, state);
            }

            before.put("itemId", Long.toString(item));
            after.put("itemId", Long.toString(item));
            row.change(newText, newDone, actor);
            recordEvent(id, actor, "CHECKLIST_CHANGED", before, after);

            return advanceVersion(id, state);
        });
    }

    public TaskDetail removeChecklistItem(long actor, long id, long item, String scope, long version) {
        return applyEdit(actor, id, scope, version, (state, task) -> {
            TaskChecklistItem row = findItem(id, item);
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("itemId", Long.toString(item));
            before.put("body", row.getBody());
            before.put("done", row.isDone());
            taskChecklistItemRepository.delete(row);
            recordEvent(id, actor, "CHECKLIST_REMOVED", before, null);

            return advanceVersion(id, state);
        });
    }

    /** Shared command frame: scope, parent lock, current editor check, then the version, in that order. */
    private TaskDetail applyEdit(
            long actor,
            long id,
            String scope,
            long version,
            BiFunction<AuthorizationState, WorkTask, TaskDetail> change
    ) {
        long branch = findVisibleTask(authorizationService.current(actor), id, scope).getBranchId();

        return authorizedCommands.inBranches(
                actor,
                Set.of(branch),
                BranchAction.READ,
                () -> workTaskRepository.lock(id),
                state -> {
                    WorkTask task = workTaskRepository.findById(id)
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

                    if (!canEdit(state, task)) {
                        throw AuthorizationService.forbidden();
                    }

                    ApiVersion.requireCurrent(version, task.getVersion(), "/api/tasks/" + id);

                    return change.apply(state, task);
                }
        );
    }

    private TaskDetail saveAndView(WorkTask task, AuthorizationState state) {
        WorkTask result = workTaskRepository.saveAndFlush(task);

        return toDetail(result, state);
    }

    private TaskDetail advanceVersion(long id, AuthorizationState state) {
        workTaskRepository.incrementVersion(id);
        WorkTask task = workTaskRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        return toDetail(task, state);
    }

    private TaskChecklistItem findItem(long task, long item) {
        return taskChecklistItemRepository.findById(item)
                .filter(row -> row.getTaskId() == task)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** 404 hides tasks outside the reader's scope, so IDs are not an existence oracle. */
    private WorkTask findVisibleTask(AuthorizationState state, long id, String scope) {
        Set<Long> selected = authorizationService.scope(state, scope, BranchAction.READ);

        return workTaskRepository.findById(id)
                .filter(task -> selected.contains(task.getBranchId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private boolean canEdit(AuthorizationState state, WorkTask task) {
        return authorizationService.permits(state, task.getBranchId(), BranchAction.READ)
                && (task.getCreatedBy() == state.employeeId()
                || task.getAssigneeEmployeeId() == state.employeeId()
                || authorizationService.permits(state, task.getBranchId(), BranchAction.MANAGE_COLLABORATION));
    }

    private void requireAssignable(long employee, long branch) {
        if (!employeeAccountService.assignable(employee, branch)) {
            throw ApiException.unprocessable("INVALID_ASSIGNEE", "해당 지점의 활성 직원만 담당자로 지정할 수 있습니다.");
        }
    }

    private void recordEvent(
            long task,
            long actor,
            String type,
            Map<String, Object> before,
            Map<String, Object> after
    ) {
        String beforeJson = before == null ? null : objectMapper.writeValueAsString(before);
        String afterJson = after == null ? null : objectMapper.writeValueAsString(after);
        taskEventRepository.save(new TaskEvent(task, actor, type, beforeJson, afterJson));
    }

    private TaskSearchRepository.Filter toSearchFilter(
            Set<Long> selected,
            String q,
            TaskFilters filters,
            long actor,
            Instant now
    ) {
        return new TaskSearchRepository.Filter(
                selected,
                q,
                filters.status(),
                filters.assignee(),
                filters.mine() ? actor : null,
                filters.dueFrom(),
                filters.dueTo(),
                filters.overdue(),
                now
        );
    }

    private List<TaskListItem> toListItems(List<WorkTask> tasks, AuthorizationState state, Instant now) {
        if (tasks.isEmpty()) {
            return List.of();
        }

        List<Long> taskIds = tasks.stream()
                .map(WorkTask::getId)
                .toList();
        Map<Long, ChecklistProgress> progress = new HashMap<>();
        taskChecklistItemRepository.findProgress(taskIds).forEach(row -> progress.put(
                (Long) row[0],
                new ChecklistProgress(((Number) row[1]).longValue(), ((Number) row[2]).longValue())
        ));
        List<Long> people = tasks.stream()
                .flatMap(task -> Stream.of(task.getAssigneeEmployeeId(), task.getCreatedBy()))
                .toList();
        Map<Long, String> names = employeeAccountService.names(people);

        return tasks.stream()
                .map(task -> toListItem(task, progress.getOrDefault(task.getId(), ChecklistProgress.NONE), names,
                        state, now))
                .toList();
    }

    private TaskListItem toListItem(
            WorkTask task,
            ChecklistProgress progress,
            Map<Long, String> names,
            AuthorizationState state,
            Instant now
    ) {
        return new TaskListItem(
                task.getId().toString(),
                Long.toString(task.getBranchId()),
                task.getTitle(),
                task.getCategory(),
                TaskInputs.toLabel(task.getPriority()),
                TaskInputs.toLabel(task.getStatus()),
                toActorSummary(task.getAssigneeEmployeeId(), names),
                toActorSummary(task.getCreatedBy(), names),
                task.getDueAt(),
                task.getCompletedAt(),
                isOverdue(task, now),
                progress.total(),
                progress.done(),
                canEdit(state, task),
                ApiVersion.fromDatabase(task.getVersion()),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    private TaskDetail toDetail(WorkTask task, AuthorizationState state) {
        List<TaskChecklistItem> rows = taskChecklistItemRepository.findByTaskIdOrderBySortOrderAscIdAsc(task.getId());
        List<Long> ids = new ArrayList<>(List.of(task.getAssigneeEmployeeId(), task.getCreatedBy()));
        rows.stream()
                .map(TaskChecklistItem::getUpdatedBy)
                .filter(Objects::nonNull)
                .forEach(ids::add);
        Map<Long, String> names = employeeAccountService.names(ids);
        List<TaskChecklistItemView> views = rows.stream()
                .map(row -> toChecklistItemView(row, names))
                .toList();
        long doneCount = rows.stream()
                .filter(TaskChecklistItem::isDone)
                .count();

        return new TaskDetail(
                task.getId().toString(),
                Long.toString(task.getBranchId()),
                task.getTitle(),
                task.getCategory(),
                TaskInputs.toLabel(task.getPriority()),
                TaskInputs.toLabel(task.getStatus()),
                toActorSummary(task.getAssigneeEmployeeId(), names),
                toActorSummary(task.getCreatedBy(), names),
                task.getDueAt(),
                task.getCompletedAt(),
                isOverdue(task, clock.instant()),
                rows.size(),
                doneCount,
                canEdit(state, task),
                ApiVersion.fromDatabase(task.getVersion()),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getBody(),
                views
        );
    }

    private static TaskChecklistItemView toChecklistItemView(TaskChecklistItem row, Map<Long, String> names) {
        ActorSummary updatedBy = row.getUpdatedBy() == null ? null : toActorSummary(row.getUpdatedBy(), names);

        return new TaskChecklistItemView(
                row.getId().toString(),
                row.getBody(),
                row.isDone(),
                row.getSortOrder(),
                updatedBy,
                row.getUpdatedAt()
        );
    }

    private TaskEventView toEventView(TaskEvent row, Map<Long, String> names) {
        ActorSummary actor = row.getActorEmployeeId() == null
                ? null
                : toActorSummary(row.getActorEmployeeId(), names);

        return new TaskEventView(
                row.getId().toString(),
                row.getEventType(),
                actor,
                toMap(row.getBeforeJson()),
                toMap(row.getAfterJson()),
                row.getCreatedAt()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(String value) {
        return value == null ? null : objectMapper.readValue(value, Map.class);
    }

    private static boolean isOverdue(WorkTask task, Instant now) {
        return task.getStatus() != TaskStatus.DONE && task.getDueAt() != null && task.getDueAt().isBefore(now);
    }

    private static void putIfChanged(
            String field,
            Object before,
            Object after,
            Map<String, Object> beforeValues,
            Map<String, Object> afterValues
    ) {
        if (Objects.equals(before, after)) {
            return;
        }

        beforeValues.put(field, before);
        afterValues.put(field, after);
    }

    private static Map<String, Object> toValues(String... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();

        for (int index = 0; index < pairs.length; index += 2) {
            values.put(pairs[index], pairs[index + 1]);
        }

        return values;
    }

    private static String toText(Instant value) {
        return value == null ? null : value.toString();
    }

    private static ActorSummary toActorSummary(long id, Map<Long, String> names) {
        return new ActorSummary(Long.toString(id), names.getOrDefault(id, ""));
    }

    private static void requireValidKey(String key) {
        boolean isValidKey = key != null && key.length() >= MIN_KEY_LENGTH && key.length() <= MAX_KEY_LENGTH;

        if (!isValidKey) {
            throw ApiException.validation("Idempotency-Key", "16~128자 키가 필요합니다.");
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM).digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
