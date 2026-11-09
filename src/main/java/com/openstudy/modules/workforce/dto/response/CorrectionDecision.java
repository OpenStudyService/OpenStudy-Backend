package com.openstudy.modules.workforce.dto.response;

import java.time.Instant;

/**
 * Contract schema {@code AttendanceCorrectionDecision}: who approved or rejected a correction and when, and the reason
 * of a rejection (null for an approval). The reason is kept in audit_events (2026-11-09 user decision).
 */
public record CorrectionDecision(ActorSummary by, Instant at, String reason) {}
