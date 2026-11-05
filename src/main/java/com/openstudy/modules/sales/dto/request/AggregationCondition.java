package com.openstudy.modules.sales.dto.request;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The request side of the contract's {@code AggregationCondition}: what a caller asks for, before the server applies its
 * defaults and the requester's permissions. The HTTP controller builds it from query parameters; M10 builds the same
 * object without HTTP (M10-D05). A null field means "omitted":
 * <ul>
 *   <li>branchIds: every branch where the requester has sales:read (M9-D07). Otherwise the order is kept for
 *       {@code sort=requested}; a duplicate or an empty list is a 422.</li>
 *   <li>from/to: both or neither. Neither is this month in Asia/Seoul up to tomorrow 00:00 (M9-D01).</li>
 *   <li>refundBasis: processedDate (M9-D02).</li>
 *   <li>purpose: all three purposes (M9-D03). productId, categoryCode and granularity belong to #88's operations and are
 *       refused where the operation does not take them.</li>
 * </ul>
 * Validation, defaults and authorization all happen behind the service boundary, so a caller that skips HTTP gets the
 * same 422 and 403 decisions.
 */
public record AggregationCondition(List<Long> branchIds, Instant from, Instant to, RefundBasis refundBasis,
        SalesPurpose purpose, Long productId, String categoryCode, AggregationGranularity granularity) {
    public AggregationCondition {
        // A null element is kept as a validation failure of the resolver, so copy without List.copyOf.
        branchIds = branchIds == null ? null : Collections.unmodifiableList(new ArrayList<>(branchIds));
    }

    /** Only the parameters of the revenue operations (#87). */
    public static AggregationCondition revenue(List<Long> branchIds, Instant from, Instant to, RefundBasis refundBasis,
            SalesPurpose purpose) {
        return new AggregationCondition(branchIds, from, to, refundBasis, purpose, null, null, null);
    }
}
