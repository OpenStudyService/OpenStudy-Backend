package com.openstudy.modules.workforce.dto.response;

import java.time.Instant;

/** Contract schema {@code AttendanceCorrectionValues}: the fields not of the correction's kind are null. */
public record AttendanceCorrectionValues(Instant clockInAt, Instant clockOutAt, Instant breakStartedAt, Instant breakEndedAt) {}
