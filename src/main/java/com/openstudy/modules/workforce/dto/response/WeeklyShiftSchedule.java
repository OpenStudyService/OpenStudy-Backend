package com.openstudy.modules.workforce.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Contract schema {@code WeeklyShiftSchedule}: the plan and what actually happened, side by side and never merged. */
public record WeeklyShiftSchedule(LocalDate weekStart, LocalDate weekEnd, String timeZone, List<ShiftView> shifts,
        List<AttendanceRecordView> attendance, Instant asOf) {}
