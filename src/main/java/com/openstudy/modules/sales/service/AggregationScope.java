package com.openstudy.modules.sales.service;

import com.openstudy.modules.sales.dto.request.AdvancedFilter;
import java.time.Instant;
import java.util.List;

import com.openstudy.modules.authorization.dto.AuthorizationState;
import com.openstudy.modules.sales.dto.request.AggregationGranularity;
import com.openstudy.modules.sales.dto.request.RefundBasis;
import com.openstudy.modules.sales.dto.request.SalesPurpose;
import com.openstudy.modules.sales.repository.SalesSourceRepository.SourceFilter;

/**
 * An {@link com.openstudy.modules.sales.dto.request.AggregationCondition} after validation, defaults and authorization:
 * every branch here has passed READ_SALES for the requester. branchIds is ascending (the response order of the condition);
 * requestedOrder keeps the order the caller sent for {@code sort=requested}. asOf is read inside the same read-only
 * transaction as every number of the response.
 */
public record AggregationScope(AuthorizationState requester, List<Long> branchIds, List<Long> requestedOrder,
        boolean allBranches, Instant from, Instant to, boolean defaultPeriod, RefundBasis refundBasis, SalesPurpose purpose,
        Long productId, String categoryCode, AggregationGranularity granularity, Instant asOf, AdvancedFilter advanced,
        boolean passOnly) {
    public AggregationScope {
        branchIds = List.copyOf(branchIds);
        requestedOrder = List.copyOf(requestedOrder);
    }

    public SourceFilter sourceFilter() {
        return new SourceFilter(branchIds, from, to, refundBasis, purpose, productId, categoryCode, advanced);
    }

    public boolean periodOpen() { return to.isAfter(asOf); }
}
