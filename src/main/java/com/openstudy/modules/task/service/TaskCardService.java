package com.openstudy.modules.task.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.authorization.dto.BranchAction;
import com.openstudy.modules.authorization.service.AuthorizationService;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.task.dto.response.ActorSummary;
import com.openstudy.modules.task.dto.response.TaskCardView;
import com.openstudy.modules.task.entity.WorkTask;
import com.openstudy.modules.task.repository.WorkTaskRepository;

/**
 * What the channel module may ask of the task module for a shared card (FR-TASK-07, M7-D07). The rule is the task
 * screen's own: a reader opens a task through a valid membership of the task's branch, so a channel never widens it.
 * Cards are read from the task row at the time of reading, for the reader asking, and cost a fixed number of queries.
 */
@Service
@RequiredArgsConstructor
public class TaskCardService {

    private final WorkTaskRepository workTaskRepository;
    private final EmployeeAccountService employeeAccountService;
    private final AuthorizationService authorizationService;

    /** Whether this reader can open the task right now; a task that does not exist is not accessible. */
    @Transactional(readOnly = true)
    public boolean accessible(AuthorizationState state, long taskId) {
        return workTaskRepository.findById(taskId)
                .filter(task -> canRead(state, task))
                .isPresent();
    }

    /** One card per requested task ID, in two queries however many there are. */
    @Transactional(readOnly = true)
    public Map<Long, TaskCardView> cards(AuthorizationState state, Collection<Long> taskIds) {
        Map<Long, TaskCardView> result = new HashMap<>();

        if (taskIds.isEmpty()) {
            return result;
        }

        List<WorkTask> found = workTaskRepository.findAllById(Set.copyOf(taskIds));
        List<Long> assigneeIds = found.stream()
                .filter(task -> canRead(state, task))
                .map(WorkTask::getAssigneeEmployeeId)
                .toList();
        Map<Long, String> names = employeeAccountService.names(assigneeIds);

        for (WorkTask task : found) {
            TaskCardView card = canRead(state, task) ? toCard(task, names) : TaskCardView.inaccessible();
            result.put(task.getId(), card);
        }

        for (Long id : taskIds) {
            result.putIfAbsent(id, TaskCardView.inaccessible());
        }

        return result;
    }

    private boolean canRead(AuthorizationState state, WorkTask task) {
        return authorizationService.permits(state, task.getBranchId(), BranchAction.READ);
    }

    private static TaskCardView toCard(WorkTask task, Map<Long, String> names) {
        long assigneeId = task.getAssigneeEmployeeId();
        ActorSummary assignee = new ActorSummary(Long.toString(assigneeId), names.getOrDefault(assigneeId, ""));

        return new TaskCardView(
                true,
                task.getId().toString(),
                Long.toString(task.getBranchId()),
                task.getTitle(),
                TaskInputs.toLabel(task.getStatus()),
                TaskInputs.toLabel(task.getPriority()),
                assignee,
                task.getDueAt()
        );
    }
}
