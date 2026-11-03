package com.openstudy.modules.workforce.repository;

import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.workforce.entity.LeaveRequest;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {
    /**
     * The employee's leave in this status touching the days [first, last], for a caller holding the employee row. A locking
     * read for the same reason as the overlapping shifts; M12 approval takes the same employee row first (M12-D03).
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT l FROM LeaveRequest l WHERE l.employeeId = :employee AND l.status = :status AND l.startsOn <= :last AND l.endsOn >= :first "
            + "ORDER BY l.startsOn, l.id")
    List<LeaveRequest> touching(@Param("employee") long employee, @Param("status") LeaveRequest.Status status,
            @Param("first") LocalDate first, @Param("last") LocalDate last);
}
