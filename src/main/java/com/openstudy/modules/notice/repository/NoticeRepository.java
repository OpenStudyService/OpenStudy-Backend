package com.openstudy.modules.notice.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.notice.entity.Notice;

public interface NoticeRepository extends JpaRepository<Notice, Long> {
    /**
     * Parent lock for AuthorizedCommands, taken after the guard/employee/branch locks.
     * JPA-01 native exception: the lock order is part of the contract, so the statement stays explicit.
     */
    @Query(value = "SELECT id FROM announcements WHERE id = :id FOR UPDATE", nativeQuery = true)
    List<Long> lock(@Param("id") long id);
}
