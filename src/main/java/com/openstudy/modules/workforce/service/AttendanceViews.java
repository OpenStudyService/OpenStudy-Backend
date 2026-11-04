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
    private final AttendanceRecordRepository attendance;
    private final AttendanceBreakRepository breaks;
    private final EmployeeAccountService employees;

    List<AttendanceRecordView> records(List<AttendanceRecord> rows) {
        if (rows.isEmpty()) return List.of();
        var breaksOf = breaksOf(rows);
        var names = employees.names(rows.stream().map(AttendanceRecord::getEmployeeId).distinct().toList());
        return rows.stream().map(row -> {
            var parts = breaksOf.getOrDefault(row.getId(), List.of());
            var employee = new ActorSummary(Long.toString(row.getEmployeeId()), Objects.requireNonNullElse(names.get(row.getEmployeeId()), ""));
            return new AttendanceRecordView(row.getId().toString(), ApiVersion.fromDatabase(row.getVersion()), AttendanceReadings.status(row, parts),
                    Long.toString(row.getBranchId()), employee, row.getShiftId() == null ? null : row.getShiftId().toString(),
                    row.getClockInAt(), row.getClockOutAt(), ShiftInputs.day(row.getClockInAt()),
                    parts.stream().map(part -> new AttendanceBreakView(part.getId().toString(), part.getStartedAt(), part.getEndedAt())).toList(),
                    AttendanceReadings.workedMinutes(row, parts));
        }).toList();
    }

    AttendanceRecordView record(AttendanceRecord row) { return records(List.of(row)).getFirst(); }

    /** What clocked in against each of these shifts, oldest first, as the shift response's summary. */
    Map<Long, List<AttendanceSummary>> linkedTo(Collection<Long> shiftIds) {
        Map<Long, List<AttendanceSummary>> attended = new HashMap<>();
        if (shiftIds.isEmpty()) return attended;
        var linked = attendance.findByShiftIdInOrderByClockInAtAscIdAsc(shiftIds);
        var breaksOf = breaksOf(linked);
        for (var record : linked) {
            var parts = breaksOf.getOrDefault(record.getId(), List.of());
            attended.computeIfAbsent(record.getShiftId(), shift -> new ArrayList<>()).add(new AttendanceSummary(record.getId().toString(),
                    AttendanceReadings.status(record, parts), record.getClockInAt(), record.getClockOutAt(), AttendanceReadings.workedMinutes(record, parts)));
        }
        return attended;
    }

    private Map<Long, List<AttendanceBreak>> breaksOf(List<AttendanceRecord> records) {
        Map<Long, List<AttendanceBreak>> result = new HashMap<>();
        if (records.isEmpty()) return result;
        breaks.findByAttendanceIdInOrderByStartedAtAscIdAsc(records.stream().map(AttendanceRecord::getId).toList())
                .forEach(part -> result.computeIfAbsent(part.getAttendanceId(), id -> new ArrayList<>()).add(part));
        return result;
    }
}
