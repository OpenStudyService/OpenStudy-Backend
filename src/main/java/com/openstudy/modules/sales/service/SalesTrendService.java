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
import com.openstudy.modules.sales.dto.response.AggregationMeta;
import com.openstudy.modules.sales.dto.response.MetricRef;
import com.openstudy.modules.sales.dto.response.PassVolume;
import com.openstudy.modules.sales.dto.response.RevenueAmounts;
import com.openstudy.modules.sales.dto.response.SalesTrend;
import com.openstudy.modules.sales.dto.response.SalesTrendBucket;
import com.openstudy.modules.sales.dto.response.SalesTrendTotals;
import com.openstudy.modules.sales.repository.SalesSourceRepository;
import com.openstudy.modules.sales.repository.SalesSourceRepository.DayTotal;
import com.openstudy.modules.sales.repository.SalesSourceRepository.PassTotal;
import com.openstudy.modules.sales.repository.SalesSourceRepository.SourceTotal;
import com.openstudy.modules.sales.service.PassTally.Volume;
import com.openstudy.modules.sales.service.RevenueTally.Amounts;

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

    private final AggregationConditions aggregationConditions;
    private final SalesSourceRepository salesSourceRepository;

    /** The buckets and totals of one measure, with the source rows behind the totals. */
    private record Folded(
            List<SalesTrendBucket> items,
            SalesTrendTotals totals,
            long sourceRows
    ) {
    }

    /** measure null is revenue and granularity null is day; both are echoed as applied. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SalesTrend trend(long requester, AggregationCondition condition, TrendMeasure measure) {
        TrendMeasure applied = measure == null ? TrendMeasure.REVENUE : measure;
        boolean revenue = applied == TrendMeasure.REVENUE;
        AggregationConditions.Target target = revenue
                ? AggregationConditions.Target.REVENUE
                : AggregationConditions.Target.PASSES;
        boolean granularityMissing = condition != null && condition.granularity() == null;
        AggregationCondition defaulted = granularityMissing
                ? condition.withGranularity(AggregationGranularity.DAY)
                : condition;
        AggregationScope scope = aggregationConditions.resolve(requester, defaulted, target, true);
        TrendBuckets buckets = new TrendBuckets(scope.from(), scope.to(), scope.granularity());
        Folded folded = revenue ? foldRevenue(scope, buckets) : foldPasses(scope, buckets);
        List<SalesTrendBucket> items = folded.items();
        List<MetricRef> metrics = revenue ? AggregationMetas.REVENUE_METRICS : AggregationMetas.PASS_METRICS;
        AggregationMeta meta = AggregationMetas.of(
                scope,
                items.size(),
                folded.sourceRows(),
                metrics,
                items.getFirst().partial(),
                items.getLast().partial()
        );

        return new SalesTrend(meta, applied.wire, scope.granularity().wire, folded.totals(), items);
    }

    private Folded foldRevenue(AggregationScope scope, TrendBuckets buckets) {
        List<TrendBuckets.Bucket> bounds = buckets.buckets();
        List<SalesTrendBucket> items = new ArrayList<>(bounds.size());
        List<List<SourceTotal>> perBucket = createBucketLists(bounds.size());
        List<SourceTotal> all = new ArrayList<>();

        for (DayTotal day : salesSourceRepository.dayTotals(scope.sourceFilter())) {
            perBucket.get(buckets.findPosition(day.day())).add(day.total());
            all.add(day.total());
        }

        List<SalesPurpose> purposes = scope.purpose() == null ? ALL_PURPOSES : List.of(scope.purpose());

        for (int index = 0; index < bounds.size(); index++) {
            Amounts amounts = new RevenueTally(perBucket.get(index)).sumAmounts(scope.branchIds(), purposes);
            items.add(toBucket(bounds.get(index), amounts.toView(), null));
        }

        Amounts sum = new RevenueTally(all).sumAmounts(scope.branchIds(), purposes);

        return new Folded(items, new SalesTrendTotals(sum.toView(), null), sum.sourceRows());
    }

    private Folded foldPasses(AggregationScope scope, TrendBuckets buckets) {
        List<TrendBuckets.Bucket> bounds = buckets.buckets();
        List<SalesTrendBucket> items = new ArrayList<>(bounds.size());
        List<List<PassTotal>> perBucket = createBucketLists(bounds.size());
        List<PassTotal> all = salesSourceRepository.passTotals(scope.sourceFilter(), true);

        for (PassTotal group : all) {
            perBucket.get(buckets.findPosition(group.day())).add(group);
        }

        for (int index = 0; index < bounds.size(); index++) {
            PassVolume volume = Volume.sum(perBucket.get(index)).toView();
            items.add(toBucket(bounds.get(index), null, volume));
        }

        Volume sum = Volume.sum(all);

        return new Folded(items, new SalesTrendTotals(null, sum.toView()), sum.sourceRows());
    }

    private static <T> List<List<T>> createBucketLists(int count) {
        List<List<T>> lists = new ArrayList<>();

        for (int index = 0; index < count; index++) {
            lists.add(new ArrayList<>());
        }

        return lists;
    }

    private static SalesTrendBucket toBucket(TrendBuckets.Bucket bounds, RevenueAmounts revenue, PassVolume passes) {
        return new SalesTrendBucket(
                bounds.label(),
                bounds.bucketStart(),
                bounds.bucketEnd(),
                bounds.start(),
                bounds.end(),
                bounds.partial(),
                revenue,
                passes
        );
    }
}
