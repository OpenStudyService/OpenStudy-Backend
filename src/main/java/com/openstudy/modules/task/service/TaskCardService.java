package com.openstudy.modules.task.service;

import java.util.Collection;
import java.util.HashMap;
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
    private final WorkTaskRepository tasks;
    private final EmployeeAccountService employees;
    private final AuthorizationService policy;

    /** Whether this reader can open the task right now; a task that does not exist is not accessible. */
    @Transactional(readOnly = true)
    public boolean accessible(AuthorizationState state, long taskId) {
        return tasks.findById(taskId).filter(task -> permits(state, task)).isPresent();
    }

    /** One card per requested task ID, in two queries however many there are. */
    @Transactional(readOnly = true)
    public Map<Long, TaskCardView> cards(AuthorizationState state, Collection<Long> taskIds) {
        Map<Long, TaskCardView> result = new HashMap<>();
        if (taskIds.isEmpty()) return result;
        var found = tasks.findAllById(Set.copyOf(taskIds));
        var names = employees.names(found.stream().filter(task -> permits(state, task)).map(WorkTask::getAssigneeEmployeeId).toList());
        for (WorkTask task : found) {
            result.put(task.getId(), !permits(state, task) ? TaskCardView.inaccessible()
                    : new TaskCardView(true, task.getId().toString(), Long.toString(task.getBranchId()), task.getTitle(),
                    TaskInputs.label(task.getStatus()), TaskInputs.label(task.getPriority()),
                    new ActorSummary(Long.toString(task.getAssigneeEmployeeId()), names.getOrDefault(task.getAssigneeEmployeeId(), "")),
                    task.getDueAt()));
        }
        for (Long id : taskIds) result.putIfAbsent(id, TaskCardView.inaccessible());
        return result;
    }

    private boolean permits(AuthorizationState state, WorkTask task) {
        return policy.permits(state, task.getBranchId(), BranchAction.READ);
    }
}
