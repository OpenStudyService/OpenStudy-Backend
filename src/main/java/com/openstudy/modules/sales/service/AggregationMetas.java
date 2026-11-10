package com.openstudy.modules.sales.service;

import com.openstudy.modules.sales.dto.response.ConditionNote;

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
    /** x-metric-ids of the pass operations and the passes trend (#88), with their units. */
    public static final List<MetricRef> PASS_METRICS = List.of(new MetricRef("pass.payment_count", "건"),
            new MetricRef("pass.sold_quantity", "개"), new MetricRef("pass.refunded_quantity", "개"),
            new MetricRef("pass.net_sold_quantity", "개"), new MetricRef("pass.issued_count", "개"));

    private AggregationMetas() {}

    /** dataStatus follows sourceRowCount. The bucket flags are null for anything but a trend. */
    public static AggregationMeta of(AggregationScope scope, long rowCount, long sourceRowCount, List<MetricRef> metrics,
            Boolean firstBucketPartial, Boolean lastBucketPartial) {
        return new AggregationMeta(condition(scope), scope.defaultPeriod(), TIME_ZONE, CURRENCY, scope.asOf(), rowCount,
                sourceRowCount, sourceRowCount == 0 ? RevenueTally.NO_DATA : RevenueTally.HAS_DATA,
                new AggregationPartial(scope.periodOpen(), firstBucketPartial, lastBucketPartial), metrics, notes(scope));
    }

    /** Store value → contract label of the advanced filters (M13-D10). */
    public static final java.util.Map<String, String> METHOD_LABELS = java.util.Map.of("CASH", "현금", "CARD", "카드", "TRANSFER", "계좌이체",
            "OTHER", "기타");
    public static final java.util.Map<String, String> KIND_LABELS = java.util.Map.of("TIME", "시간권", "PERIOD", "기간권", "STORED_VALUE", "정액권");

    /** A note for every condition that changes what a figure means (FR-AGG-15, operations-extension-api.md "응답의 적용 필터"). */
    public static List<ConditionNote> notes(AggregationScope scope) {
        var notes = new java.util.ArrayList<ConditionNote>();
        var advanced = scope.advanced();
        if (scope.passOnly()) notes.add(new ConditionNote("PASS_ONLY", "상품 유형·분류 조건으로 이용권 결제만 집계했습니다."));
        if (!advanced.processingBranchIds().isEmpty())
            notes.add(new ConditionNote("REFUND_PROCESSING_BRANCH", "환불은 처리 지점 조건에 맞는 것만 뺐고, 승인은 처리 지점과 무관합니다."));
        if (!advanced.operatorIds().isEmpty())
            notes.add(new ConditionNote("OPERATOR_BY_ENTRY", "승인은 결제 처리자, 환불은 환불 처리자로 골랐습니다."));
        if (!advanced.methods().isEmpty())
            notes.add(new ConditionNote("REFUND_BY_PAYMENT_METHOD", "환불은 원결제의 결제 수단으로 나눴습니다."));
        return notes;
    }

    public static AggregationConditionView condition(AggregationScope scope) {
        return new AggregationConditionView(scope.branchIds().stream().map(String::valueOf).toList(), scope.allBranches(),
                scope.from(), scope.to(), scope.refundBasis().wire, scope.purpose() == null ? null : scope.purpose().label,
                scope.productId() == null ? null : scope.productId().toString(), scope.categoryCode(),
                scope.granularity() == null ? null : scope.granularity().wire,
                scope.advanced().methods().stream().map(METHOD_LABELS::get).toList(),
                scope.advanced().productKinds().stream().map(KIND_LABELS::get).toList(),
                scope.advanced().operatorIds().stream().map(String::valueOf).toList(),
                scope.advanced().processingBranchIds().stream().map(String::valueOf).toList());
    }
}
