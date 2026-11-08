package com.openstudy.modules.workforce.repository;

import java.util.Collection;
import java.util.List;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.workforce.entity.LeaveAllocation;

public interface LeaveAllocationRepository extends JpaRepository<LeaveAllocation, Long> {
    List<LeaveAllocation> findByLeaveRequestIdInOrderByIdAsc(Collection<Long> requestIds);

    /** The allocations of one approval, FOR UPDATE, after the request row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM LeaveAllocation a WHERE a.leaveRequestId = :request ORDER BY a.leaveGrantId")
    List<LeaveAllocation> lockedFor(@Param("request") long request);
}
