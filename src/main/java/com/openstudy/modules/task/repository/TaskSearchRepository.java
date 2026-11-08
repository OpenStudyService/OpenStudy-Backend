package com.openstudy.modules.task.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import com.openstudy.modules.task.entity.TaskEvent;
import com.openstudy.modules.task.entity.TaskStatus;
import com.openstudy.modules.task.entity.WorkTask;

/**
 * Branch-scoped task reads. The list and the summary build their WHERE clause here, from one filter, so a count can
 * never disagree with the list it stands for. Only fixed fragments are concatenated: the sort column comes from a
 * closed enum and every value is bound.
 */
@Repository
@RequiredArgsConstructor
public class TaskSearchRepository {
    private final EntityManager entityManager;

    public enum Sort {
        DUE_AT("t.due_at"), PRIORITY("t.priority"), CREATED_AT("t.created_at"), UPDATED_AT("t.updated_at");
        final String column;
        Sort(String column) { this.column = column; }
    }

    /** {@code mine} is the requester's id when only their tasks are wanted, otherwise null. */
    public record Filter(Set<Long> branches, String q, TaskStatus status, Long assignee, Long mine, Instant dueFrom,
            Instant dueTo, boolean overdue, Instant now) {}

    public record Page(List<WorkTask> rows, long total) {}

    public record BranchCounts(long branchId, long total, long todo, long inProgress, long done, long overdue) {}

    @SuppressWarnings("unchecked")
    public Page find(Filter filter, Sort sort, boolean descending, long offset, int limit) {
        if (filter.branches().isEmpty()) return new Page(List.of(), 0);
        var where = new StringBuilder();
        var binds = new ArrayList<Object>();
        where(filter, where, binds);
        String order = descending ? " DESC" : " ASC";
        // Tasks without a due date stay last whichever way the column is sorted.
        String nulls = sort == Sort.DUE_AT ? "t.due_at IS NULL, " : "";
        Query rows = entityManager.createNativeQuery("SELECT t.* FROM work_tasks t WHERE " + where
                + " ORDER BY " + nulls + sort.column + order + ", t.id" + order + " LIMIT ? OFFSET ?", WorkTask.class);
        Query count = entityManager.createNativeQuery("SELECT COUNT(*) FROM work_tasks t WHERE " + where);
        bind(rows, binds);
        bind(count, binds);
        rows.setParameter(binds.size() + 1, limit);
        rows.setParameter(binds.size() + 2, offset);
        return new Page(rows.getResultList(), ((Number) count.getSingleResult()).longValue());
    }

    @SuppressWarnings("unchecked")
    public List<BranchCounts> counts(Filter filter) {
        if (filter.branches().isEmpty()) return List.of();
        var where = new StringBuilder();
        var binds = new ArrayList<Object>();
        binds.add(filter.now());
        where(filter, where, binds);
        Query query = entityManager.createNativeQuery("SELECT t.branch_id, COUNT(*), SUM(t.status = 'TODO'), "
                + "SUM(t.status = 'IN_PROGRESS'), SUM(t.status = 'DONE'), SUM(t.status <> 'DONE' AND t.due_at < ?) "
                + "FROM work_tasks t WHERE " + where + " GROUP BY t.branch_id");
        bind(query, binds);
        return ((List<Object[]>) query.getResultList()).stream().map(row -> new BranchCounts(number(row[0]), number(row[1]),
                number(row[2]), number(row[3]), number(row[4]), number(row[5]))).toList();
    }

    /**
     * Tasks of the filter completed in [from, to), for the home card (M9-D08). GET /tasks has no completion time filter,
     * so this count is the home's own; it uses the same WHERE clause as the list for every other condition.
     */
    public long countCompleted(Filter filter, Instant from, Instant to) {
        if (filter.branches().isEmpty()) return 0;
        var where = new StringBuilder();
        var binds = new ArrayList<Object>();
        where(filter, where, binds);
        binds.add(from);
        binds.add(to);
        Query query = entityManager.createNativeQuery("SELECT COUNT(*) FROM work_tasks t WHERE " + where
                + " AND t.status = 'DONE' AND t.completed_at >= ? AND t.completed_at < ?");
        bind(query, binds);
        return number(query.getSingleResult());
    }

    @SuppressWarnings("unchecked")
    public List<TaskEvent> events(long task, long offset, int limit) {
        Query rows = entityManager.createNativeQuery(
                "SELECT e.* FROM task_events e WHERE e.task_id = ? ORDER BY e.id DESC LIMIT ? OFFSET ?", TaskEvent.class);
        rows.setParameter(1, task);
        rows.setParameter(2, limit);
        rows.setParameter(3, offset);
        return rows.getResultList();
    }

    private static void where(Filter filter, StringBuilder where, List<Object> binds) {
        var branches = filter.branches().stream().sorted().toList();
        where.append("t.branch_id IN (").append(String.join(",", Collections.nCopies(branches.size(), "?"))).append(")");
        binds.addAll(branches);
        if (filter.status() != null) { where.append(" AND t.status = ?"); binds.add(filter.status().name()); }
        if (filter.assignee() != null) { where.append(" AND t.assignee_employee_id = ?"); binds.add(filter.assignee()); }
        if (filter.mine() != null) { where.append(" AND t.assignee_employee_id = ?"); binds.add(filter.mine()); }
        if (filter.dueFrom() != null) { where.append(" AND t.due_at >= ?"); binds.add(filter.dueFrom()); }
        if (filter.dueTo() != null) { where.append(" AND t.due_at < ?"); binds.add(filter.dueTo()); }
        if (filter.overdue()) { where.append(" AND t.status <> 'DONE' AND t.due_at < ?"); binds.add(filter.now()); }
        if (!filter.q().isEmpty()) {
            where.append(" AND (t.title LIKE ? ESCAPE '!' OR t.body LIKE ? ESCAPE '!')");
            binds.add("%" + like(filter.q()) + "%");
            binds.add("%" + like(filter.q()) + "%");
        }
    }

    private static void bind(Query query, List<Object> binds) {
        for (int index = 0; index < binds.size(); index++) query.setParameter(index + 1, binds.get(index));
    }

    private static long number(Object value) { return value == null ? 0 : ((Number) value).longValue(); }

    private static String like(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
