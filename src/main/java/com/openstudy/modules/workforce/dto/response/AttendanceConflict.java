package com.openstudy.modules.workforce.dto.response;

import java.time.Instant;

/**
 * Contract schema {@code AttendanceConflict}: only the part of the corrected stretch that another attendance of the same
 * employee holds. One the requester cannot see carries no id and is merged with its neighbours (M12-D08, as M8-D06).
 */
public record AttendanceConflict(boolean visible, String label, Instant overlapStartsAt, Instant overlapEndsAt, String attendanceRecordId) {}
