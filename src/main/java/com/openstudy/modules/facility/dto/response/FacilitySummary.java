package com.openstudy.modules.facility.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Contract schema {@code FacilitySummary}: every count equals the total of the same GET /facilities query with that
 * status, or with inspectionDue=true for {@code inspectionDue}.
 */
public record FacilitySummary(Instant asOf, Counts totals, List<BranchCounts> branches) {
    public record Counts(long total, long normal, long checkNeeded, long broken, long outOfService, long inspectionDue) {}
    public record BranchCounts(String branchId, long total, long normal, long checkNeeded, long broken, long outOfService,
            long inspectionDue) {}
}
