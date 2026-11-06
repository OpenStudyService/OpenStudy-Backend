package com.openstudy.modules.sales.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.modules.sales.dto.request.AggregationCondition;
import com.openstudy.modules.sales.dto.request.AggregationGranularity;
import com.openstudy.modules.sales.dto.request.SalesPurpose;
import com.openstudy.modules.sales.dto.request.TrendMeasure;
import com.openstudy.modules.sales.dto.response.PassVolume;
import com.openstudy.modules.sales.dto.response.RevenueAmounts;
import com.openstudy.modules.sales.dto.response.SalesTrend;
import com.openstudy.modules.sales.dto.response.SalesTrendBucket;
import com.openstudy.modules.sales.dto.response.SalesTrendTotals;
import com.openstudy.modules.sales.repository.SalesSourceRepository;
import com.openstudy.modules.sales.repository.SalesSourceRepository.PassTotal;
import com.openstudy.modules.sales.repository.SalesSourceRepository.SourceTotal;
import com.openstudy.modules.sales.service.PassTally.Volume;

/**
 * The day/week/month trend (#88, aggregation-api.md "추세", M9-D04) of revenue or pass quantities. Source rows are read
 * once, grouped by Asia/Seoul day, and folded into the buckets; totals are the sum of the same groups, so Σ buckets =
 * totals = the summary operation of the same condition (GET /sales/revenue or GET /sales/passes/volume). One read-only
 * REPEATABLE READ transaction per call, the M10 boundary like the other sales services.
 */
@Service
@RequiredArgsConstructor
public class SalesTrendService {
    private static final List<SalesPurpose> ALL_PURPOSES = Arrays.asList(SalesPurpose.values());

    private final AggregationConditions conditions;
    private final SalesSourceRepository sources;

    /** measure null is revenue and granularity null is day; both are echoed as applied. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SalesTrend trend(long requester, AggregationCondition condition, TrendMeasure measure) {
        var applied = measure == null ? TrendMeasure.REVENUE : measure;
        var target = applied == TrendMeasure.REVENUE ? AggregationConditions.Target.REVENUE : AggregationConditions.Target.PASSES;
        var defaulted = condition != null && condition.granularity() == null
                ? condition.withGranularity(AggregationGranularity.DAY) : condition;
        var scope = conditions.resolve(requester, defaulted, target, true);
        var buckets = new TrendBuckets(scope.from(), scope.to(), scope.granularity());
        var bounds = buckets.buckets();
        var items = new ArrayList<SalesTrendBucket>(bounds.size());
        SalesTrendTotals totals;
        long sourceRows;
        if (applied == TrendMeasure.REVENUE) {
            var perBucket = new ArrayList<List<SourceTotal>>();
            for (int i = 0; i < bounds.size(); i++) perBucket.add(new ArrayList<>());
            var all = new ArrayList<SourceTotal>();
            for (var day : sources.dayTotals(scope.sourceFilter())) {
                perBucket.get(buckets.of(day.day())).add(day.total());
                all.add(day.total());
            }
            var purposes = scope.purpose() == null ? ALL_PURPOSES : List.of(scope.purpose());
            for (int i = 0; i < bounds.size(); i++) {
                var amounts = new RevenueTally(perBucket.get(i)).of(scope.branchIds(), purposes);
                items.add(bucket(bounds.get(i), amounts.view(), null));
            }
            var sum = new RevenueTally(all).of(scope.branchIds(), purposes);
            totals = new SalesTrendTotals(sum.view(), null);
            sourceRows = sum.sourceRows();
        } else {
            var perBucket = new ArrayList<List<PassTotal>>();
            for (int i = 0; i < bounds.size(); i++) perBucket.add(new ArrayList<>());
            var all = sources.passTotals(scope.sourceFilter(), true);
            for (var group : all) perBucket.get(buckets.of(group.day())).add(group);
            for (int i = 0; i < bounds.size(); i++) items.add(bucket(bounds.get(i), null, Volume.sum(perBucket.get(i)).view()));
            var sum = Volume.sum(all);
            totals = new SalesTrendTotals(null, sum.view());
            sourceRows = sum.sourceRows();
        }
        var metrics = applied == TrendMeasure.REVENUE ? AggregationMetas.REVENUE_METRICS : AggregationMetas.PASS_METRICS;
        var meta = AggregationMetas.of(scope, items.size(), sourceRows, metrics, items.getFirst().partial(),
                items.getLast().partial());
        return new SalesTrend(meta, applied.wire, scope.granularity().wire, totals, items);
    }

    private static SalesTrendBucket bucket(TrendBuckets.Bucket bounds, RevenueAmounts revenue, PassVolume passes) {
        return new SalesTrendBucket(bounds.label(), bounds.bucketStart(), bounds.bucketEnd(), bounds.start(), bounds.end(),
                bounds.partial(), revenue, passes);
    }
}
