package com.openstudy.modules.sales.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.openstudy.common.web.ListQuery;
import com.openstudy.common.web.PageResponse;
import com.openstudy.modules.sales.dto.request.AggregationCondition;
import com.openstudy.modules.sales.dto.response.PassProductSales;
import com.openstudy.modules.sales.dto.response.PassProductSalesPage;
import com.openstudy.modules.sales.dto.response.PassVolumeSummary;
import com.openstudy.modules.sales.repository.SalesSourceRepository;
import com.openstudy.modules.sales.repository.SalesSourceRepository.PassTotal;
import com.openstudy.modules.sales.service.PassTally.Ranked;
import com.openstudy.modules.sales.service.PassTally.Snapshot;
import com.openstudy.modules.sales.service.PassTally.Volume;

/**
 * Pass volume and the product ranking (#88, aggregation-api.md "이용권 판매량"·"상품별 판매량·순위"): only purpose PASS,
 * filtered by productId and the sale-time categoryCode, never by purpose. Like {@link SalesRevenueService} it is the
 * boundary M10 calls with an {@link AggregationCondition} (M10-D05), and each call is one read-only REPEATABLE READ
 * transaction, so totals, rows and metadata come from one snapshot.
 */
@Service
@RequiredArgsConstructor
public class PassSalesService {

    private static final int VOLUME_ROW_COUNT = 1;

    private final AggregationConditions aggregationConditions;
    private final SalesSourceRepository salesSourceRepository;

    /** GET /sales/passes/volume: the three pass numbers kept apart (M9-D06). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PassVolumeSummary volume(long requester, AggregationCondition condition) {
        AggregationScope scope = aggregationConditions.resolve(
                requester,
                condition,
                AggregationConditions.Target.PASSES,
                false
        );
        Volume totals = Volume.sum(salesSourceRepository.passTotals(scope.sourceFilter(), false));

        return new PassVolumeSummary(
                AggregationMetas.of(
                        scope,
                        VOLUME_ROW_COUNT,
                        totals.sourceRows(),
                        AggregationMetas.PASS_METRICS,
                        null,
                        null
                ),
                totals.toView()
        );
    }

    /**
     * GET /sales/passes/products: one row per sale-time snapshot with an approval or refund in the period, in the fixed
     * order with competition ranks. Ranks are computed over every row before paging, so a page shows the same rank as
     * the whole list; totals and meta.total cover every row.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PassProductSalesPage products(long requester, AggregationCondition condition, ListQuery page) {
        AggregationScope scope = aggregationConditions.resolve(
                requester,
                condition,
                AggregationConditions.Target.PASSES,
                false
        );
        List<PassTotal> groups = salesSourceRepository.passTotals(scope.sourceFilter(), false);
        List<PassProductSales> ranked = toRankedRows(groups);
        long total = ranked.size();
        boolean pageBeyondTotal = page.offset() >= total;
        List<PassProductSales> items = pageBeyondTotal
                ? List.of()
                : ranked.subList((int) page.offset(), (int) Math.min(total, page.offset() + page.pageSize()));
        Volume totals = Volume.sum(groups);

        return new PassProductSalesPage(
                items,
                new PageResponse.Meta(page.page(), page.pageSize(), total),
                AggregationMetas.of(scope, total, totals.sourceRows(), AggregationMetas.PASS_METRICS, null, null),
                totals.toView()
        );
    }

    private static List<PassProductSales> toRankedRows(List<PassTotal> groups) {
        Map<Snapshot, Volume> bySnapshot = new LinkedHashMap<>();

        for (PassTotal group : groups) {
            bySnapshot.merge(Snapshot.of(group), Volume.of(group), Volume::plus);
        }

        List<Ranked> rows = new ArrayList<>();
        bySnapshot.forEach((snapshot, volume) -> rows.add(new Ranked(snapshot, volume)));
        rows.sort(PassTally.ORDER);

        List<PassProductSales> ranked = new ArrayList<>(rows.size());
        long rank = 0;

        for (int position = 0; position < rows.size(); position++) {
            Ranked row = rows.get(position);
            // Competition rank: a tie shares the rank and the next value skips as many (10, 7, 7, 3 -> 1, 2, 2, 4).
            boolean first = position == 0;
            boolean newValue = first
                    || rows.get(position - 1).volume().netSoldQuantity() != row.volume().netSoldQuantity();

            if (newValue) {
                rank = position + 1;
            }

            ranked.add(toView(rank, row));
        }

        return ranked;
    }

    private static PassProductSales toView(long rank, Ranked row) {
        Snapshot snapshot = row.snapshot();
        Volume volume = row.volume();

        return new PassProductSales(
                rank,
                Long.toString(snapshot.productId()),
                snapshot.productName(),
                snapshot.categoryCode(),
                snapshot.categoryName(),
                snapshot.kindLabel(),
                volume.paymentCount(),
                volume.soldQuantity(),
                volume.refundedQuantity(),
                volume.netSoldQuantity(),
                volume.issuedCount(),
                volume.dataStatus()
        );
    }
}
