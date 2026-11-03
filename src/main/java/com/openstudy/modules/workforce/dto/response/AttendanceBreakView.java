package com.openstudy.modules.workforce.dto.response;

import java.time.Instant;

/** Contract schema {@code AttendanceBreak}. A null endedAt is the break in progress. */
public record AttendanceBreakView(String id, Instant startedAt, Instant endedAt) {}
