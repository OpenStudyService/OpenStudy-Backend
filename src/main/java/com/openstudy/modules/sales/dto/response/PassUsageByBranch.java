package com.openstudy.modules.sales.dto.response;

import java.util.List;

/** Contract schema {@code PassUsageByBranch}: pass usage per using branch, split by selling branch (M13-D12). Not revenue. */
public record PassUsageByBranch(AggregationMeta aggregation, Amounts totals, List<Branch> items) {
    public PassUsageByBranch { items = List.copyOf(items); }

    /** Contract schema {@code PassUsageAmounts}: pass.usage_session_count, pass.usage_minutes, pass.usage_credit_krw. */
    public record Amounts(long sessionCount, long usedMinutes, long usedCreditKrw) {}

    /** Contract schema {@code PassUsageBranch}. */
    public record Branch(String branchId, long sessionCount, long usedMinutes, long usedCreditKrw, List<SaleBranch> bySaleBranch) {}

    /** Contract schema {@code PassUsageSaleBranch}: a selling branch the reader cannot see is merged into one hidden row. */
    public record SaleBranch(String saleBranchId, boolean visible, String label, long sessionCount, long usedMinutes, long usedCreditKrw) {}
}
