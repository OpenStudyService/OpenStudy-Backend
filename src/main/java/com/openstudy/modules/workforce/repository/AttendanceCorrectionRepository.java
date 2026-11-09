package com.openstudy.modules.workforce.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.workforce.entity.AttendanceCorrection;

public interface AttendanceCorrectionRepository extends JpaRepository<AttendanceCorrection, Long> {
    /** SELECT ... FOR UPDATE, after the requester's employee row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM AttendanceCorrection c WHERE c.id = :id")
    Optional<AttendanceCorrection> locked(@Param("id") long id);

    /** The attendance's corrections in this status, a locking read after the employee row: one undecided correction per attendance. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT c FROM AttendanceCorrection c WHERE c.attendanceRecordId = :attendance AND c.status = :status ORDER BY c.id")
    List<AttendanceCorrection> forAttendance(@Param("attendance") long attendance, @Param("status") AttendanceCorrection.Status status);
}
