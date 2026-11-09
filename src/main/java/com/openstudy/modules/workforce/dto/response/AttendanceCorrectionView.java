package com.openstudy.modules.workforce.dto.response;

import java.time.Instant;

/**
 * Contract schema {@code AttendanceCorrection}: the values at request time (before), the corrected ones (after), the
 * decision and the withdrawal (FR-CORR-04). The can* fields only guide the screen.
 */
public record AttendanceCorrectionView(String id, long version, String status, String kind, String attendanceRecordId, String branchId,
        ActorSummary employee, String breakId, AttendanceCorrectionValues before, AttendanceCorrectionValues after, String reason,
        CorrectionDecision decision, RequestCancellation cancellation, Instant createdAt, boolean canApprove, boolean canReject,
        boolean canCancel) {}
