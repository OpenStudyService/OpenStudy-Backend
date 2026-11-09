package com.openstudy.modules.workforce.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.workforce.entity.AttendanceRecord;

public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Long> {
    /** What clocked in against these shifts. A link is always the same employee and branch as its shift (M8-D08). */
    List<AttendanceRecord> findByShiftIdInOrderByClockInAtAscIdAsc(Collection<Long> shiftIds);

    /** The employee's attendance in progress at any branch, for reading only: at most one (uk_attendance_records_1). */
    Optional<AttendanceRecord> findFirstByEmployeeIdAndStatus(long employeeId, AttendanceRecord.Status status);

    /**
     * JPA-01 native exception: the generated active_employee_id is not mapped, and its unique key is the one index that
     * names "the employee's attendance in progress" exactly. FOR UPDATE, so a clock command sees the latest committed
     * state and holds the row it changes; under the guard and the employee row nothing else is waiting on this key.
     */
    @Query(value = "SELECT * FROM attendance_records WHERE active_employee_id=:employee FOR UPDATE", nativeQuery = true)
    Optional<AttendanceRecord> lockedCurrent(@Param("employee") long employeeId);

    /** One attendance FOR UPDATE, after its employee's row: an approved correction changes it (#107). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AttendanceRecord a WHERE a.id = :id")
    Optional<AttendanceRecord> locked(@Param("id") long id);

    /** One attendance FOR SHARE, after its employee's row: a correction request reads it and changes nothing (#107). */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT a FROM AttendanceRecord a WHERE a.id = :id")
    Optional<AttendanceRecord> shared(@Param("id") long id);

    /**
     * The employee's other attendance at every branch that may meet [start, end): closed ones that overlap it and every
     * one still open, whose end is "now" for the caller to judge (M12-D08). A locking read under the employee row.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT a FROM AttendanceRecord a WHERE a.employeeId = :employee AND a.id <> :self AND a.clockInAt < :end "
            + "AND (a.clockOutAt IS NULL OR a.clockOutAt > :start) ORDER BY a.clockInAt, a.id")
    List<AttendanceRecord> meeting(@Param("employee") long employee, @Param("self") long self, @Param("start") Instant start,
            @Param("end") Instant end);
}
