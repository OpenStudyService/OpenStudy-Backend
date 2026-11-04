package com.openstudy.modules.workforce.repository;

import java.util.Collection;
import java.util.List;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.workforce.entity.AttendanceBreak;

public interface AttendanceBreakRepository extends JpaRepository<AttendanceBreak, Long> {
    List<AttendanceBreak> findByAttendanceIdInOrderByStartedAtAscIdAsc(Collection<Long> attendanceIds);

    /**
     * The attendance's breaks, read under lock by a holder of the attendance row: a locking read answers from the latest
     * committed rows, not from a snapshot taken before the row lock was granted.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM AttendanceBreak b WHERE b.attendanceId = :attendance ORDER BY b.startedAt, b.id")
    List<AttendanceBreak> locked(@Param("attendance") long attendanceId);
}
