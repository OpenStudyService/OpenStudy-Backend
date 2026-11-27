package com.openstudy.modules.workforce.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.openstudy.common.web.ApiVersion;
import com.openstudy.modules.employee.service.EmployeeAccountService;
import com.openstudy.modules.workforce.dto.response.ActorSummary;
import com.openstudy.modules.workforce.dto.response.AttendanceBreakView;
import com.openstudy.modules.workforce.dto.response.AttendanceRecordView;
import com.openstudy.modules.workforce.dto.response.AttendanceSummary;
import com.openstudy.modules.workforce.entity.AttendanceBreak;
import com.openstudy.modules.workforce.entity.AttendanceRecord;
import com.openstudy.modules.workforce.repository.AttendanceBreakRepository;
import com.openstudy.modules.workforce.repository.AttendanceRecordRepository;

/**
 * The one mapping of attendance rows to responses: the attendance list and detail, the clock commands, the weekly
 * schedule and a shift's linked attendance all go through here and through {@link AttendanceReadings}, so a status or
 * a worked-minute value cannot differ between two screens (M8-D07).
 */
@Component
@RequiredArgsConstructor
class AttendanceViews {

    private static final String UNKNOWN_NAME = "";

    private final AttendanceRecordRepository attendanceRecordRepository;
    private final AttendanceBreakRepository attendanceBreakRepository;
    private final EmployeeAccountService employeeAccountService;

    List<AttendanceRecordView> toViews(List<AttendanceRecord> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        Map<Long, List<AttendanceBreak>> breaksOf = findBreaksOf(rows);
        List<Long> employeeIds = rows.stream()
                .map(AttendanceRecord::getEmployeeId)
                .distinct()
                .toList();
        Map<Long, String> names = employeeAccountService.names(employeeIds);

        return rows.stream()
                .map(row -> toView(row, breaksOf.getOrDefault(row.getId(), List.of()), names))
                .toList();
    }

    AttendanceRecordView toView(AttendanceRecord row) {
        return toViews(List.of(row)).getFirst();
    }

    /** What clocked in against each of these shifts, oldest first, as the shift response's summary. */
    Map<Long, List<AttendanceSummary>> findLinkedTo(Collection<Long> shiftIds) {
        Map<Long, List<AttendanceSummary>> attended = new HashMap<>();

        if (shiftIds.isEmpty()) {
            return attended;
        }

        List<AttendanceRecord> linked = attendanceRecordRepository.findByShiftIdInOrderByClockInAtAscIdAsc(shiftIds);
        Map<Long, List<AttendanceBreak>> breaksOf = findBreaksOf(linked);

        for (AttendanceRecord record : linked) {
            List<AttendanceBreak> parts = breaksOf.getOrDefault(record.getId(), List.of());
            AttendanceSummary summary = new AttendanceSummary(
                    record.getId().toString(),
                    AttendanceReadings.toStatus(record, parts),
                    record.getClockInAt(),
                    record.getClockOutAt(),
                    AttendanceReadings.calculateWorkedMinutes(record, parts)
            );
            attended.computeIfAbsent(record.getShiftId(), shift -> new ArrayList<>())
                    .add(summary);
        }

        return attended;
    }

    private static AttendanceRecordView toView(
            AttendanceRecord row,
            List<AttendanceBreak> parts,
            Map<Long, String> names
    ) {
        String employeeName = Objects.requireNonNullElse(names.get(row.getEmployeeId()), UNKNOWN_NAME);
        ActorSummary employee = new ActorSummary(Long.toString(row.getEmployeeId()), employeeName);
        List<AttendanceBreakView> breakViews = parts.stream()
                .map(part -> new AttendanceBreakView(part.getId().toString(), part.getStartedAt(), part.getEndedAt()))
                .toList();

        return new AttendanceRecordView(
                row.getId().toString(),
                ApiVersion.fromDatabase(row.getVersion()),
                AttendanceReadings.toStatus(row, parts),
                Long.toString(row.getBranchId()),
                employee,
                row.getShiftId() == null ? null : row.getShiftId().toString(),
                row.getClockInAt(),
                row.getClockOutAt(),
                ShiftInputs.toDay(row.getClockInAt()),
                breakViews,
                AttendanceReadings.calculateWorkedMinutes(row, parts)
        );
    }

    private Map<Long, List<AttendanceBreak>> findBreaksOf(List<AttendanceRecord> records) {
        Map<Long, List<AttendanceBreak>> result = new HashMap<>();

        if (records.isEmpty()) {
            return result;
        }

        List<Long> attendanceIds = records.stream()
                .map(AttendanceRecord::getId)
                .toList();
        attendanceBreakRepository.findByAttendanceIdInOrderByStartedAtAscIdAsc(attendanceIds)
                .forEach(part -> result.computeIfAbsent(part.getAttendanceId(), id -> new ArrayList<>()).add(part));

        return result;
    }
}
