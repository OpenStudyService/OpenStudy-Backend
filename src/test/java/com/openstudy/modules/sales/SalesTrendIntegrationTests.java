package com.openstudy.modules.sales;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.JsonNode;

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.ListQuery;
import com.openstudy.modules.sales.dto.request.AggregationCondition;
import com.openstudy.modules.sales.dto.request.AggregationGranularity;
import com.openstudy.modules.sales.dto.request.RefundBasis;
import com.openstudy.modules.sales.dto.request.SalesPurpose;
import com.openstudy.modules.sales.dto.request.TrendMeasure;
import com.openstudy.modules.sales.service.PassSalesService;
import com.openstudy.modules.sales.service.SalesTrendService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #88, the day/week/month trend (M9-D04): the worked example's week table, Monday weeks, every bucket with empty
 * ones as 0 and noData, partial buckets at the period edges, Asia/Seoul midnights, and Σ buckets = totals = the summary
 * operation of the same condition for revenue and pass quantities on both refund bases. Also the #88 operations through
 * the service boundary M10 calls and their wire shapes against the contract.
 */
class SalesTrendIntegrationTests extends SalesIntegrationSupport {
    @Autowired SalesTrendService trends;
    @Autowired PassSalesService passes;

    @Test void theWorkedExampleWeekTableIsReproduced() throws Exception {
        workedExample();
        try (var s = login(reader)) {
            var body = ok(s, "/api/sales/trend?" + branchIds(first) + "&" + november() + "&granularity=week");
            var items = body.get("items");
            assertThat(labels(items)).containsExactly("2026-10-26", "2026-11-02", "2026-11-09", "2026-11-16", "2026-11-23", "2026-11-30");
            long[][] expected = {{6_000, 0, 6_000}, {20_000, 16_000, 4_000}, {12_000, 0, 12_000}, {0, 0, 0}, {0, 0, 0}, {15_000, 0, 15_000}};
            long[][] counts = {{1, 0}, {1, 2}, {1, 0}, {0, 0}, {0, 0}, {1, 0}};
            for (int i = 0; i < expected.length; i++) {
                var revenue = items.get(i).get("revenue");
                amounts(revenue, expected[i][0], expected[i][1], expected[i][2], counts[i][0], counts[i][1],
                        counts[i][0] + counts[i][1] == 0 ? "noData" : "hasData");
                assertThat(items.get(i).get("passes").isNull()).isTrue();
            }
            assertThat(partials(items)).containsExactly(true, false, false, false, false, true);
            // The first week starts on Monday 10-26 but is counted from 11-01; the last ends 12-07 but is counted to 12-01.
            assertThat(items.at("/0/bucketStart").asString()).isEqualTo("2026-10-25T15:00:00Z");
            assertThat(items.at("/0/start").asString()).isEqualTo("2026-10-31T15:00:00Z");
            assertThat(items.at("/0/end").asString()).isEqualTo("2026-11-01T15:00:00Z");
            assertThat(items.at("/5/bucketEnd").asString()).isEqualTo("2026-12-06T15:00:00Z");
            assertThat(items.at("/5/end").asString()).isEqualTo("2026-11-30T15:00:00Z");
            assertThat(items.at("/1/start").asString()).isEqualTo(items.at("/1/bucketStart").asString());
            amounts(body.at("/totals/revenue"), 53_000, 16_000, 37_000, 4, 2, "hasData");
            assertThat(body.at("/totals/passes").isNull()).isTrue();
            var meta = body.get("aggregation");
            assertThat(meta.at("/partial/firstBucketPartial").asBoolean()).isTrue();
            assertThat(meta.at("/partial/lastBucketPartial").asBoolean()).isTrue();
            assertThat(meta.at("/partial/periodOpen").asBoolean()).isFalse();
            assertThat(meta.get("rowCount").asLong()).isEqualTo(6);
            assertThat(meta.get("sourceRowCount").asLong()).isEqualTo(6);
            assertThat(meta.at("/condition/granularity").asString()).isEqualTo("week");
            assertThat(body.get("measure").asString()).isEqualTo("revenue");
            assertThat(body.get("granularity").asString()).isEqualTo("week");

            var passWeeks = ok(s, "/api/sales/trend?" + branchIds(first) + "&" + november() + "&granularity=week&measure=passes");
            var weeks = passWeeks.get("items");
            volume(weeks.get(0).get("passes"), 0, 0, 0, 0, 0, "noData");
            volume(weeks.get(1).get("passes"), 0, 0, 1, -1, 0, "hasData");
            volume(weeks.get(2).get("passes"), 1, 1, 0, 1, 1, "hasData");
            volume(weeks.get(3).get("passes"), 0, 0, 0, 0, 0, "noData");
            volume(weeks.get(5).get("passes"), 1, 1, 0, 1, 1, "hasData");
            assertThat(weeks.get(0).get("revenue").isNull()).isTrue();
            volume(passWeeks.at("/totals/passes"), 2, 2, 1, 1, 2, "hasData");
            assertThat(passWeeks.at("/totals/revenue").isNull()).isTrue();
            assertThat(passWeeks.at("/aggregation/sourceRowCount").asLong()).isEqualTo(3);
            assertThat(passWeeks.at("/aggregation/metrics").toString()).contains("pass.net_sold_quantity").doesNotContain("sales.");
        }
    }

    @Test void bucketsSumToTheSummaryOperationForEveryGranularityMeasureAndBasis() throws Exception {
        workedExample();
        try (var s = login(reader)) {
            var periods = List.of(november(), period("2026-10-15T00:00", "2026-12-10T00:00"),
                    period("2026-10-31T23:00", "2026-12-01T00:30"), period("2026-01-01T00:00", "2027-01-02T00:00"));
            for (String period : periods) {
                for (String basis : List.of("processedDate", "paymentDate")) {
                    String query = branchIds(first, second) + "&" + period + "&refundBasis=" + basis;
                    var revenue = ok(s, "/api/sales/revenue?" + query).get("totals");
                    var volume = ok(s, "/api/sales/passes/volume?" + query).get("totals");
                    for (String granularity : List.of("day", "week", "month")) {
                        var byRevenue = ok(s, "/api/sales/trend?" + query + "&granularity=" + granularity);
                        assertThat(byRevenue.at("/totals/revenue")).as(query + granularity).isEqualTo(revenue);
                        assertThat(sum(byRevenue.get("items"), "revenue", "approvedAmountKrw")).isEqualTo(revenue.get("approvedAmountKrw").asLong());
                        assertThat(sum(byRevenue.get("items"), "revenue", "refundAmountKrw")).isEqualTo(revenue.get("refundAmountKrw").asLong());
                        assertThat(sum(byRevenue.get("items"), "revenue", "approvedPaymentCount")).isEqualTo(revenue.get("approvedPaymentCount").asLong());
                        assertThat(sum(byRevenue.get("items"), "revenue", "refundCount")).isEqualTo(revenue.get("refundCount").asLong());
                        var byPasses = ok(s, "/api/sales/trend?" + query + "&granularity=" + granularity + "&measure=passes");
                        assertThat(byPasses.at("/totals/passes")).as(query + granularity).isEqualTo(volume);
                        for (String field : List.of("paymentCount", "soldQuantity", "refundedQuantity", "netSoldQuantity", "issuedCount"))
                            assertThat(sum(byPasses.get("items"), "passes", field)).as(query + granularity + field).isEqualTo(volume.get(field).asLong());
                        assertThat(byRevenue.at("/aggregation/rowCount").asLong()).isEqualTo(byRevenue.get("items").size());
                    }
                }
            }
            // The purpose filter applies to every bucket and to the totals.
            String room = branchIds(first) + "&" + november() + "&purpose=" + enc("스터디룸");
            var rooms = ok(s, "/api/sales/trend?" + room + "&granularity=month");
            amounts(rooms.at("/items/0/revenue"), 6_000, 6_000, 0, 1, 1, "hasData");
            assertThat(rooms.at("/aggregation/condition/purpose").asString()).isEqualTo("스터디룸");
        }
    }

    @Test void everyBucketOfThePeriodIsReturnedInSeoulDaysWithEmptyOnesAsNoData() throws Exception {
        workedExample();
        try (var s = login(reader)) {
            var days = ok(s, "/api/sales/trend?" + branchIds(first) + "&" + november()).get("items");
            assertThat(days).hasSize(30);
            assertThat(days.at("/0/label").asString()).isEqualTo("2026-11-01");
            assertThat(days.at("/29/label").asString()).isEqualTo("2026-11-30");
            assertThat(partials(days)).containsOnly(false);
            // P2 was approved 11-01 00:30 in Seoul, 10-31 in UTC: the Seoul day decides.
            amounts(days.get(0).get("revenue"), 6_000, 0, 6_000, 1, 0, "hasData");
            // P3 at 11-30 23:59:59 is the last day's.
            amounts(days.get(29).get("revenue"), 15_000, 0, 15_000, 1, 0, "hasData");
            amounts(days.get(1).get("revenue"), 0, 10_000, -10_000, 0, 1, "hasData");
            amounts(days.get(15).get("revenue"), 0, 0, 0, 0, 0, "noData");

            // A period that starts and ends inside a day cuts both edge days.
            var cut = ok(s, "/api/sales/trend?" + branchIds(first) + "&" + period("2026-11-01T00:15", "2026-11-03T12:30"));
            var edges = cut.get("items");
            assertThat(labels(edges)).containsExactly("2026-11-01", "2026-11-02", "2026-11-03");
            assertThat(partials(edges)).containsExactly(true, false, true);
            assertThat(edges.at("/0/start").asString()).isEqualTo("2026-10-31T15:15:00Z");
            assertThat(edges.at("/0/bucketStart").asString()).isEqualTo("2026-10-31T15:00:00Z");
            assertThat(edges.at("/2/end").asString()).isEqualTo("2026-11-03T03:30:00Z");
            assertThat(edges.at("/2/bucketEnd").asString()).isEqualTo("2026-11-03T15:00:00Z");
            amounts(edges.get(0).get("revenue"), 6_000, 0, 6_000, 1, 0, "hasData");
            // P2's refund at 11-03 12:00 is inside the cut last day.
            amounts(edges.get(2).get("revenue"), 0, 6_000, -6_000, 0, 1, "hasData");
            assertThat(cut.at("/aggregation/partial/firstBucketPartial").asBoolean()).isTrue();
            assertThat(cut.at("/aggregation/partial/lastBucketPartial").asBoolean()).isTrue();

            // Weeks start on Monday: 11-02 and 11-09 are whole weeks, a Sunday start is the end of the previous week.
            var mondays = ok(s, "/api/sales/trend?" + branchIds(first) + "&" + period("2026-11-02T00:00", "2026-11-16T00:00")
                    + "&granularity=week").get("items");
            assertThat(labels(mondays)).containsExactly("2026-11-02", "2026-11-09");
            assertThat(partials(mondays)).containsExactly(false, false);
            var sunday = ok(s, "/api/sales/trend?" + branchIds(first) + "&" + period("2026-11-08T00:00", "2026-11-10T00:00")
                    + "&granularity=week").get("items");
            assertThat(labels(sunday)).containsExactly("2026-11-02", "2026-11-09");
            assertThat(partials(sunday)).containsExactly(true, true);

            // Months: YYYY-MM labels, cut at both ends, the whole month in the middle.
            var months = ok(s, "/api/sales/trend?" + branchIds(first) + "&" + period("2026-10-15T00:00", "2026-12-10T00:00")
                    + "&granularity=month").get("items");
            assertThat(labels(months)).containsExactly("2026-10", "2026-11", "2026-12");
            assertThat(partials(months)).containsExactly(true, false, true);
            assertThat(months.at("/1/bucketStart").asString()).isEqualTo("2026-10-31T15:00:00Z");
            assertThat(months.at("/1/bucketEnd").asString()).isEqualTo("2026-11-30T15:00:00Z");
            amounts(months.get(1).get("revenue"), 53_000, 16_000, 37_000, 4, 2, "hasData");

            // A branch without transactions still gets every bucket.
            var nothing = ok(s, "/api/sales/trend?" + branchIds(second) + "&" + november() + "&granularity=week");
            assertThat(nothing.get("items")).hasSize(6);
            assertThat(nothing.at("/aggregation/dataStatus").asString()).isEqualTo("noData");
            for (var item : nothing.get("items")) amounts(item.get("revenue"), 0, 0, 0, 0, 0, "noData");

            // The longest period: 366 days is 366 day buckets, 367 when it starts off midnight; at most 54 weeks, 13 months.
            String year = branchIds(first) + "&" + period("2026-01-01T00:00", "2027-01-02T00:00");
            assertThat(ok(s, "/api/sales/trend?" + year).get("items")).hasSize(366);
            assertThat(ok(s, "/api/sales/trend?" + branchIds(first) + "&" + period("2026-01-01T12:00", "2027-01-02T12:00"))
                    .get("items")).hasSize(367);
            assertThat(ok(s, "/api/sales/trend?" + year + "&granularity=week").get("items")).hasSizeLessThanOrEqualTo(54);
            assertThat(ok(s, "/api/sales/trend?" + year + "&granularity=month").get("items")).hasSize(13);
        }
    }

    @Test void theDefaultsAreAppliedAndEchoed() throws Exception {
        workedExample();
        clock.fixed = seoul("2026-11-05T10:00");
        try (var s = login(reader)) {
            var body = ok(s, "/api/sales/trend?" + branchIds(first));
            assertThat(body.get("measure").asString()).isEqualTo("revenue");
            assertThat(body.get("granularity").asString()).isEqualTo("day");
            var meta = body.get("aggregation");
            assertThat(meta.at("/condition/granularity").asString()).isEqualTo("day");
            assertThat(meta.at("/condition/refundBasis").asString()).isEqualTo("processedDate");
            assertThat(meta.get("defaultPeriod").asBoolean()).isTrue();
            assertThat(meta.at("/partial/periodOpen").asBoolean()).isTrue();
            // [11-01, 11-06): five whole days.
            assertThat(labels(body.get("items"))).containsExactly("2026-11-01", "2026-11-02", "2026-11-03", "2026-11-04", "2026-11-05");
            assertThat(meta.at("/partial/firstBucketPartial").asBoolean()).isFalse();
            assertThat(meta.at("/partial/lastBucketPartial").asBoolean()).isFalse();
            assertThat(meta.get("metrics")).hasSize(5);
        }
    }

    @Test void eachMeasureTakesOnlyItsOwnFilters() throws Exception {
        try (var s = login(reader)) {
            String one = branchIds(first) + "&" + november();
            for (String query : List.of("productId=1", "categoryCode=TIME", "measure=revenue&productId=1",
                    "measure=passes&purpose=" + enc("이용권"), "measure=sales", "measure=", "granularity=hour",
                    "granularity=WEEK", "granularity=")) {
                var refused = get(s, "/api/sales/trend?" + one + "&" + query);
                assertThat(refused.statusCode()).as(query).isEqualTo(422);
                assertThat(node(refused).get("code").asString()).as(query).isEqualTo("VALIDATION_FAILED");
            }
            var tooLong = get(s, "/api/sales/trend?" + branchIds(first) + "&" + period("2026-01-01T00:00", "2027-01-02T00:00:01"));
            assertThat(node(tooLong).get("code").asString()).isEqualTo("PERIOD_TOO_LONG");
            var filtered = ok(s, "/api/sales/trend?" + one + "&measure=passes&categoryCode=" + categoryCode());
            assertThat(filtered.at("/aggregation/condition/categoryCode").asString()).isEqualTo(categoryCode());
            assertThat(filtered.at("/aggregation/condition/purpose").asString()).isEqualTo("이용권");
        }
        try (var s = login(firstOnly)) {
            var refused = get(s, "/api/sales/trend?" + branchIds(first, second) + "&" + november());
            assertThat(refused.statusCode()).isEqualTo(403);
            assertThat(refused.body()).doesNotContain("items", "aggregation");
        }
        try (var s = login(nobody)) {
            assertThat(get(s, "/api/sales/trend?" + november()).statusCode()).isEqualTo(403);
        }
    }

    @Test void m10CallsThePassAndTrendServicesWithTheConditionObjectAndGetsTheSameAnswer() throws Exception {
        workedExample();
        Instant from = seoul("2026-11-01T00:00"), to = seoul("2026-12-01T00:00");
        var condition = AggregationCondition.passes(List.of(first, second), from, to, RefundBasis.PAYMENT_DATE, null, null);
        try (var s = login(reader)) {
            String query = branchIds(first, second) + "&" + november() + "&refundBasis=paymentDate";
            assertThat(tree(passes.volume(reader, condition))).isEqualTo(ok(s, "/api/sales/passes/volume?" + query));
            var page = ListQuery.parse(1, 20, null, null, null, java.util.Set.of("netSoldQuantity"), "netSoldQuantity");
            assertThat(tree(passes.products(reader, condition, page))).isEqualTo(ok(s, "/api/sales/passes/products?" + query));
            assertThat(tree(trends.trend(reader, condition.withGranularity(AggregationGranularity.WEEK), TrendMeasure.PASSES)))
                    .isEqualTo(ok(s, "/api/sales/trend?" + query + "&granularity=week&measure=passes"));
            var revenue = AggregationCondition.revenue(List.of(first, second), from, to, RefundBasis.PAYMENT_DATE, null);
            assertThat(tree(trends.trend(reader, revenue, null))).isEqualTo(ok(s, "/api/sales/trend?" + query));
        }
        assertThatThrownBy(() -> passes.volume(firstOnly, AggregationCondition.passes(List.of(second), from, to, null, null, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> trends.trend(nobody, AggregationCondition.revenue(null, from, to, null, null), null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> passes.volume(reader, new AggregationCondition(List.of(first), from, to, null, SalesPurpose.PASS,
                null, null, null))).isInstanceOf(ApiException.class);
        // Granularity belongs to the trend only.
        assertThatThrownBy(() -> passes.volume(reader, AggregationCondition.passes(List.of(first), from, to, null, null, null)
                .withGranularity(AggregationGranularity.DAY))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> trends.trend(reader, AggregationCondition.passes(List.of(first), from, to, null, 1L, null),
                TrendMeasure.REVENUE)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> trends.trend(reader, null, null)).isInstanceOf(ApiException.class);
    }

    @Test void everyResponseCarriesExactlyTheContractFields() throws Exception {
        workedExample();
        var spec = json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("docs/design/foundation/openapi.json")));
        try (var s = login(reader)) {
            String query = branchIds(first, second) + "&" + november();
            var volume = ok(s, "/api/sales/passes/volume?" + query);
            var products = ok(s, "/api/sales/passes/products?" + query);
            var revenueTrend = ok(s, "/api/sales/trend?" + query + "&granularity=week");
            var passTrend = ok(s, "/api/sales/trend?" + query + "&measure=passes");
            same(spec, "PassVolumeSummary", volume);
            same(spec, "PassVolume", volume.get("totals"));
            same(spec, "PassProductSalesPage", products);
            same(spec, "PassProductSales", products.at("/items/0"));
            same(spec, "PageMeta", products.get("meta"));
            same(spec, "SalesTrend", revenueTrend);
            same(spec, "SalesTrendBucket", revenueTrend.at("/items/0"));
            same(spec, "RevenueAmounts", revenueTrend.at("/items/0/revenue"));
            same(spec, "PassVolume", passTrend.at("/items/0/passes"));
            var totals = new java.util.ArrayList<String>();
            spec.at("/components/schemas/SalesTrend/properties/totals/properties").properties().forEach(e -> totals.add(e.getKey()));
            assertThat(fields(revenueTrend.get("totals"))).containsExactlyInAnyOrderElementsOf(totals);
            for (var body : List.of(volume, products, revenueTrend, passTrend)) {
                same(spec, "AggregationMeta", body.get("aggregation"));
                same(spec, "AggregationCondition", body.at("/aggregation/condition"));
                same(spec, "AggregationPartial", body.at("/aggregation/partial"));
                same(spec, "MetricRef", body.at("/aggregation/metrics/0"));
            }
        }
    }

    void same(JsonNode spec, String schema, JsonNode actual) {
        var expected = new java.util.ArrayList<String>();
        spec.at("/components/schemas/" + schema + "/properties").properties().forEach(entry -> expected.add(entry.getKey()));
        assertThat(fields(actual)).as(schema).containsExactlyInAnyOrderElementsOf(expected);
    }

    JsonNode tree(Object value) { return json.readTree(json.writeValueAsString(value)); }

    static List<String> labels(JsonNode items) {
        var labels = new java.util.ArrayList<String>();
        for (var item : items) labels.add(item.get("label").asString());
        return labels;
    }

    static List<Boolean> partials(JsonNode items) {
        var partials = new java.util.ArrayList<Boolean>();
        for (var item : items) partials.add(item.get("partial").asBoolean());
        return partials;
    }

    static long sum(JsonNode items, String measure, String field) {
        long sum = 0;
        for (var item : items) sum += item.get(measure).get(field).asLong();
        return sum;
    }
}
