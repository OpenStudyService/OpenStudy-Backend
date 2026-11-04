package com.openstudy.modules.workforce.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
