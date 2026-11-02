package com.openstudy.modules.locker.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.locker.entity.LockerAssignmentCharge;

public interface LockerAssignmentChargeRepository extends JpaRepository<LockerAssignmentCharge, Long> {
    /** In the order they were made, so the last one is the latest attempt (space-operations-api.md). */
    List<LockerAssignmentCharge> findByAssignmentIdInOrderByIdAsc(Collection<Long> assignmentIds);
}
