package com.openstudy.modules.workforce.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.workforce.entity.AttendanceRecord;

public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Long> {
    /** What clocked in against these shifts. A link is always the same employee and branch as its shift (M8-D08). */
    List<AttendanceRecord> findByShiftIdInOrderByClockInAtAscIdAsc(Collection<Long> shiftIds);
}
