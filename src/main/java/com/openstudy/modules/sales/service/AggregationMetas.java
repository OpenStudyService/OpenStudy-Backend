package com.openstudy.modules.sales.service;

import java.util.List;

import com.openstudy.modules.sales.dto.response.AggregationConditionView;
import com.openstudy.modules.sales.dto.response.AggregationMeta;
import com.openstudy.modules.sales.dto.response.AggregationPartial;
import com.openstudy.modules.sales.dto.response.MetricRef;

/** The common response metadata (FR-AGG-09) and the metric lists of x-metric-definitions, shared by every aggregation. */
public final class AggregationMetas {
    public static final String TIME_ZONE = "Asia/Seoul";
    public static final String CURRENCY = "KRW";
    /** x-metric-ids of the revenue operations, with their units. */
    public static final List<MetricRef> REVENUE_METRICS = List.of(new MetricRef("sales.approved_amount", "원"),
            new MetricRef("sales.refund_amount", "원"), new MetricRef("sales.net_amount", "원"),
            new MetricRef("sales.approved_payment_count", "건"), new MetricRef("sales.refund_count", "건"));

    private AggregationMetas() {}

    /** dataStatus follows sourceRowCount. The bucket flags are null for anything but a trend. */
    public static AggregationMeta of(AggregationScope scope, long rowCount, long sourceRowCount, List<MetricRef> metrics,
            Boolean firstBucketPartial, Boolean lastBucketPartial) {
        return new AggregationMeta(condition(scope), scope.defaultPeriod(), TIME_ZONE, CURRENCY, scope.asOf(), rowCount,
                sourceRowCount, sourceRowCount == 0 ? RevenueTally.NO_DATA : RevenueTally.HAS_DATA,
                new AggregationPartial(scope.periodOpen(), firstBucketPartial, lastBucketPartial), metrics);
    }

    public static AggregationConditionView condition(AggregationScope scope) {
        return new AggregationConditionView(scope.branchIds().stream().map(String::valueOf).toList(), scope.allBranches(),
                scope.from(), scope.to(), scope.refundBasis().wire, scope.purpose() == null ? null : scope.purpose().label,
                scope.productId() == null ? null : scope.productId().toString(), scope.categoryCode(),
                scope.granularity() == null ? null : scope.granularity().wire);
    }
}
