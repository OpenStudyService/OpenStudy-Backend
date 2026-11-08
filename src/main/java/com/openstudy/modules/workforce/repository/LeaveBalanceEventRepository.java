package com.openstudy.modules.workforce.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.workforce.entity.LeaveBalanceEvent;

public interface LeaveBalanceEventRepository extends JpaRepository<LeaveBalanceEvent, Long> {
    List<LeaveBalanceEvent> findByLeaveGrantIdOrderByIdAsc(long leaveGrantId);

    /** The RESTORE rows of approval cancellations, which carry who cancelled and when. */
    List<LeaveBalanceEvent> findByLeaveRequestIdInAndEventTypeOrderByIdAsc(Collection<Long> requestIds, LeaveBalanceEvent.Type type);
}
