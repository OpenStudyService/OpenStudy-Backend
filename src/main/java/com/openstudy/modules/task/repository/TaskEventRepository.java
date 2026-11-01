package com.openstudy.modules.task.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.task.entity.TaskEvent;

public interface TaskEventRepository extends JpaRepository<TaskEvent, Long> {
    long countByTaskId(long taskId);
}
