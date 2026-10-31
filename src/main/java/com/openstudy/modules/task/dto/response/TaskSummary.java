package com.openstudy.modules.task.dto.response;

import java.time.Instant;
import java.util.List;

/** Contract schema {@code TaskSummary}: every count equals the total of the same GET /tasks query. */
public record TaskSummary(Instant asOf, Counts totals, List<BranchCounts> branches) {
    public record Counts(long total, long todo, long inProgress, long done, long overdue) {}
    public record BranchCounts(String branchId, long total, long todo, long inProgress, long done, long overdue) {}
}
