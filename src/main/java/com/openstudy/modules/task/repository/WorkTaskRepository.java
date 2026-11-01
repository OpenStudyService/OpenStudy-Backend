package com.openstudy.modules.task.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.task.entity.WorkTask;

public interface WorkTaskRepository extends JpaRepository<WorkTask, Long> {
    /**
     * Parent lock for AuthorizedCommands, taken after the guard/employee/branch locks and before checklist rows.
     * JPA-01 native exception: the lock order is part of the contract, so the statement stays explicit.
     */
    @Query(value = "SELECT id FROM work_tasks WHERE id = :id FOR UPDATE", nativeQuery = true)
    List<Long> lock(@Param("id") long id);

    /** A checklist change leaves the task row otherwise untouched; the caller re-reads the cleared entity afterwards. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE WorkTask t SET t.version = t.version + 1 WHERE t.id = :id")
    int incrementVersion(@Param("id") long id);
}
