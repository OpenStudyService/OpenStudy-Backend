package com.openstudy.modules.workforce.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.workforce.entity.AttendanceBreak;

public interface AttendanceBreakRepository extends JpaRepository<AttendanceBreak, Long> {
    List<AttendanceBreak> findByAttendanceIdInOrderByStartedAtAscIdAsc(Collection<Long> attendanceIds);
}
