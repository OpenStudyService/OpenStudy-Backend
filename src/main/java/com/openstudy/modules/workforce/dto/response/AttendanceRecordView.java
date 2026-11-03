package com.openstudy.modules.workforce.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Contract schema {@code AttendanceRecord}. workedMinutes is null until clock-out (M8-D07). */
public record AttendanceRecordView(String id, long version, String status, String branchId, ActorSummary employee, String shiftId,
        Instant clockInAt, Instant clockOutAt, LocalDate workDate, List<AttendanceBreakView> breaks, Long workedMinutes) {}
