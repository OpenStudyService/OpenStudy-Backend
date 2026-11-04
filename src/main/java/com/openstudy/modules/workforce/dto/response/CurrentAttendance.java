package com.openstudy.modules.workforce.dto.response;

import java.time.Instant;

/** Contract schema {@code CurrentAttendance}: the requester's attendance in progress, or null, as of asOf. */
public record CurrentAttendance(AttendanceRecordView attendance, Instant asOf) {}
