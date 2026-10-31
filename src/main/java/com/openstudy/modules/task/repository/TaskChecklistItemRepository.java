package com.openstudy.modules.task.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.task.entity.TaskChecklistItem;

public interface TaskChecklistItemRepository extends JpaRepository<TaskChecklistItem, Long> {
    List<TaskChecklistItem> findByTaskIdOrderBySortOrderAscIdAsc(long taskId);

    long countByTaskId(long taskId);

    @Query("SELECT COALESCE(MAX(i.sortOrder), -1) FROM TaskChecklistItem i WHERE i.taskId = :task")
    int lastSortOrder(@Param("task") long task);

    /** Rows of task id, item total and finished items, for one list page. */
    @Query("SELECT i.taskId, COUNT(i), SUM(CASE WHEN i.done = true THEN 1L ELSE 0L END) FROM TaskChecklistItem i "
            + "WHERE i.taskId IN :tasks GROUP BY i.taskId")
    List<Object[]> progress(@Param("tasks") Collection<Long> tasks);
}
