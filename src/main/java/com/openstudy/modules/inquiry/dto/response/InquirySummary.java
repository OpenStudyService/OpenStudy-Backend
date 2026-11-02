package com.openstudy.modules.inquiry.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Contract schema {@code InquirySummary}: every count equals the total of the same GET /inquiries query with that status;
 * {@code unresolved} is received plus in progress.
 */
public record InquirySummary(Instant asOf, Counts totals, List<BranchCounts> branches) {
    public record Counts(long total, long received, long inProgress, long answered, long unresolved) {}
    public record BranchCounts(String branchId, long total, long received, long inProgress, long answered, long unresolved) {}
}
