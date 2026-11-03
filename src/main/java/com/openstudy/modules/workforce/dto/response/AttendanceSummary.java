package com.openstudy.modules.workforce.dto.response;

import java.time.Instant;

/** Contract schema {@code AttendanceSummary}: an actual clock-in linked to a shift. workedMinutes is null until clock-out. */
public record AttendanceSummary(String id, String status, Instant clockInAt, Instant clockOutAt, Long workedMinutes) {}
