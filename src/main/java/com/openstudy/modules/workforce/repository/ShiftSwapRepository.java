package com.openstudy.modules.workforce.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.workforce.entity.LeaveRequest;
import com.openstudy.modules.workforce.entity.ShiftSwap;

public interface ShiftSwapRepository extends JpaRepository<ShiftSwap, Long> {
    /** SELECT ... FOR UPDATE, after the employee rows. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM ShiftSwap w WHERE w.id = :id")
    Optional<ShiftSwap> locked(@Param("id") long id);

    /** The shift's swaps in this status, a locking read after the employee rows: one undecided swap per shift. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT w FROM ShiftSwap w WHERE w.originalShiftId = :shift AND w.status = :status ORDER BY w.id")
    List<ShiftSwap> forShift(@Param("shift") long shift, @Param("status") LeaveRequest.Status status);
}
