package com.openstudy.modules.workforce.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Contract schema {@code Shift}: the plan, not what happened. Actual clock-ins are {@link AttendanceSummary} entries in
 * attendance. canEdit is a screen hint; every write checks again.
 */
public record ShiftView(String id, long version, String status, String branchId, ActorSummary employee, Instant startsAt, Instant endsAt,
        LocalDate workDate, int plannedBreakMinutes, long plannedWorkMinutes, ShiftCancellation cancellation, List<AttendanceSummary> attendance,
        ActorSummary createdBy, Instant createdAt, Instant updatedAt, boolean canEdit) {}
