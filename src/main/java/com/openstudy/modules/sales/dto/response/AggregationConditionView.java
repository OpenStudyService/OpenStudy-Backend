package com.openstudy.modules.sales.dto.response;

import java.time.Instant;
import java.util.List;

/** Contract schema {@code AggregationCondition}: the condition actually applied. branchIds ascending, from/to in UTC. */
public record AggregationConditionView(List<String> branchIds, boolean allBranches, Instant from, Instant to,
        String refundBasis, String purpose, String productId, String categoryCode, String granularity) {
    public AggregationConditionView { branchIds = List.copyOf(branchIds); }
}
