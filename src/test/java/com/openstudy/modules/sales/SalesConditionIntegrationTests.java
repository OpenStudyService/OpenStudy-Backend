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
import com.openstudy.modules.sales.dto.request.BranchRevenueSort;
import com.openstudy.modules.sales.dto.request.RefundBasis;
import com.openstudy.modules.sales.dto.request.SalesPurpose;
import com.openstudy.modules.sales.service.AggregationConditions;
import com.openstudy.modules.sales.service.SalesRevenueService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #87, the common aggregation conditions: the Asia/Seoul period (M9-D01), branch permission (M9-D07) and the
 * metadata, over HTTP and through the service boundary M10 calls without HTTP (M10-D05).
 */
class SalesConditionIntegrationTests extends SalesIntegrationSupport {
    static final List<String> OPERATIONS = List.of("/api/sales/revenue", "/api/sales/revenue/branches", "/api/sales/ledger");

    @Autowired SalesRevenueService service;
    @Autowired AggregationConditions conditions;

    @Test void theDefaultPeriodIsThisMonthUpToTomorrowInSeoul() throws Exception {
        workedExample();
        // 2026-11-05 10:00 Asia/Seoul: [11-01 00:00, 11-06 00:00) = [10-31T15:00Z, 11-05T15:00Z).
        clock.fixed = seoul("2026-11-05T10:00");
        try (var s = login(reader)) {
            for (String operation : OPERATIONS) {
                var meta = ok(s, operation + "?" + branchIds(first)).get("aggregation");
                assertThat(meta.at("/condition/from").asString()).isEqualTo("2026-10-31T15:00:00Z");
                assertThat(meta.at("/condition/to").asString()).isEqualTo("2026-11-05T15:00:00Z");
                assertThat(meta.get("defaultPeriod").asBoolean()).isTrue();
                assertThat(meta.at("/partial/periodOpen").asBoolean()).isTrue();
                assertThat(meta.get("asOf").asString()).isEqualTo("2026-11-05T01:00:00Z");
            }
            // P2 (UTC 10-31) and P5 are in; P1's refund processed 11-02 is in; P3, P6 are later.
            amounts(ok(s, "/api/sales/revenue?" + branchIds(first)).get("totals"), 26_000, 16_000, 10_000, 2, 2, "hasData");
        }
        // On the 1st the default period is that one day.
        clock.fixed = seoul("2026-11-01T00:00");
        try (var s = login(reader)) {
            var onFirst = ok(s, "/api/sales/revenue?" + branchIds(first)).get("aggregation");
            assertThat(onFirst.at("/condition/from").asString()).isEqualTo("2026-10-31T15:00:00Z");
            assertThat(onFirst.at("/condition/to").asString()).isEqualTo("2026-11-01T15:00:00Z");
        }
        // An explicit, closed period: not the default, not open.
        clock.fixed = AS_OF;
        try (var s = login(reader)) {
            var closed = ok(s, "/api/sales/revenue?" + branchIds(first) + "&" + november()).get("aggregation");
            assertThat(closed.get("defaultPeriod").asBoolean()).isFalse();
            assertThat(closed.at("/partial/periodOpen").asBoolean()).isFalse();
            assertThat(closed.at("/partial/firstBucketPartial").isNull()).isTrue();
            assertThat(closed.at("/partial/lastBucketPartial").isNull()).isTrue();
            assertThat(closed.at("/condition/from").asString()).isEqualTo("2026-10-31T15:00:00Z");
            assertThat(closed.at("/condition/to").asString()).isEqualTo("2026-11-30T15:00:00Z");
            assertThat(closed.get("timeZone").asString()).isEqualTo("Asia/Seoul");
            assertThat(closed.get("currency").asString()).isEqualTo("KRW");
            assertThat(closed.at("/condition/granularity").isNull()).isTrue();
            assertThat(closed.at("/condition/productId").isNull()).isTrue();
            assertThat(closed.at("/condition/categoryCode").isNull()).isTrue();
            // The same period written in UTC is the same condition.
            var utc = ok(s, "/api/sales/revenue?" + branchIds(first) + "&from=2026-10-31T15:00:00Z&to=2026-11-30T15:00:00Z");
            assertThat(utc.get("totals")).isEqualTo(ok(s, "/api/sales/revenue?" + branchIds(first) + "&" + november()).get("totals"));
        }
    }

    @Test void midnightInSeoulDecidesThePeriodAndTheEndIsExcluded() throws Exception {
        workedExample();
        try (var s = login(reader)) {
            // P2 at 11-01 00:30 Seoul is 10-31 15:30 UTC: November, not October.
            String nov1 = period("2026-11-01T00:00", "2026-11-02T00:00");
            amounts(ok(s, "/api/sales/revenue?" + branchIds(first) + "&" + nov1 + "&purpose=" + enc("스터디룸")).get("totals"),
                    6_000, 0, 6_000, 1, 0, "hasData");
            var octoberRoom = ok(s, "/api/sales/revenue?" + branchIds(first) + "&" + october() + "&purpose=" + enc("스터디룸"));
            amounts(octoberRoom.get("totals"), 0, 0, 0, 0, 0, "noData");
            // [from, to): a sale at exactly to is out, one microsecond later the period holds it.
            String upTo = "&from=" + enc("2026-11-01T00:00+09:00") + "&purpose=" + enc("스터디룸") + "&to=";
            assertThat(ok(s, "/api/sales/revenue?" + branchIds(first) + upTo + enc("2026-11-01T00:30+09:00"))
                    .at("/totals/approvedPaymentCount").asLong()).isZero();
            assertThat(ok(s, "/api/sales/revenue?" + branchIds(first) + upTo + enc("2026-11-01T00:30:00.000001+09:00"))
                    .at("/totals/approvedPaymentCount").asLong()).isEqualTo(1);
            assertThat(ok(s, "/api/sales/revenue?" + branchIds(first) + "&from=" + enc("2026-11-01T00:30+09:00") + "&to="
                    + enc("2026-11-01T00:30:00.000001+09:00")).at("/totals/approvedPaymentCount").asLong()).isEqualTo(1);
            // P7 at 12-01 00:00:00 Seoul is the end of November and the start of December.
            amounts(ok(s, "/api/sales/revenue?" + branchIds(second) + "&" + november()).get("totals"), 0, 0, 0, 0, 0, "noData");
            amounts(ok(s, "/api/sales/revenue?" + branchIds(second) + "&" + december()).get("totals"), 9_000, 0, 9_000, 1, 0, "hasData");
            // P3 at 11-30 23:59:59 Seoul is November.
            amounts(ok(s, "/api/sales/revenue?" + branchIds(first) + "&" + period("2026-11-30T00:00", "2026-12-01T00:00")).get("totals"),
                    15_000, 0, 15_000, 1, 0, "hasData");
            // A refund across the boundary: P1 sold 10-31 23:30, refunded 11-02 → November by processedDate, October by paymentDate.
            String nov2 = period("2026-11-02T00:00", "2026-11-03T00:00");
            amounts(ok(s, "/api/sales/revenue?" + branchIds(first) + "&" + nov2).get("totals"), 0, 10_000, -10_000, 0, 1, "hasData");
            amounts(ok(s, "/api/sales/revenue?" + branchIds(first) + "&" + nov2 + "&refundBasis=paymentDate").get("totals"),
                    0, 0, 0, 0, 0, "noData");
            String oct31 = period("2026-10-31T00:00", "2026-11-01T00:00");
            amounts(ok(s, "/api/sales/revenue?" + branchIds(first) + "&" + oct31 + "&refundBasis=paymentDate").get("totals"),
                    10_000, 10_000, 0, 1, 1, "hasData");
        }
    }

    @Test void periodsUpTo366DaysPassAndLongerOnesAre422() throws Exception {
        try (var s = login(reader)) {
            String one = branchIds(first) + "&";
            assertThat(get(s, "/api/sales/revenue?" + one + period("2026-01-01T00:00", "2027-01-01T00:00")).statusCode()).isEqualTo(200);
            assertThat(get(s, "/api/sales/revenue?" + one + period("2026-01-01T00:00", "2027-01-02T00:00")).statusCode()).isEqualTo(200);
            assertThat(get(s, "/api/sales/revenue?" + one + period("2028-01-01T00:00", "2029-01-01T00:00")).statusCode()).isEqualTo(200);
            for (String operation : OPERATIONS) {
                for (String tooLong : List.of(period("2026-01-01T00:00", "2027-01-02T00:00:01"),
                        period("2026-01-01T00:00", "2027-01-02T00:00:00.000001"), period("2028-01-01T00:00", "2029-01-02T00:00"))) {
                    var refused = get(s, operation + "?" + one + tooLong);
                    assertThat(refused.statusCode()).as(operation + tooLong).isEqualTo(422);
                    assertThat(node(refused).get("code").asString()).isEqualTo("PERIOD_TOO_LONG");
                    assertThat(node(refused).at("/fieldErrors/to").isMissingNode()).isFalse();
                    assertThat(refused.body()).doesNotContain("approvedAmountKrw", "aggregation");
                }
            }
        }
    }

    @Test void malformedConditionsAre422() throws Exception {
        try (var s = login(reader)) {
            String one = branchIds(first);
            var cases = List.of(
                    one + "&" + period("2026-11-05T00:00", "2026-11-05T00:00"),
                    one + "&" + period("2026-11-06T00:00", "2026-11-05T00:00"),
                    one + "&from=2026-11-01T00:00&to=" + enc("2026-11-06T00:00+09:00"),
                    one + "&from=" + enc("2026-11-01T00:00+09:00"),
                    one + "&to=" + enc("2026-11-01T00:00+09:00"),
                    one + "&from=2026-11-01&to=2026-11-02",
                    branchIds(first, first),
                    "branchIds=abc", "branchIds=0", "branchIds=", "branchIds=1,2",
                    one + "&refundBasis=processed", one + "&purpose=PASS", one + "&purpose=" + enc("좌석"),
                    one + "&productId=1",
                    // M13-D10 (#116): a category narrows revenue to passes, so only a contradicting purpose is refused now.
                    one + "&categoryCode=TIME&purpose=" + enc("스터디룸"));
            for (String operation : OPERATIONS) {
                for (String query : cases) {
                    var refused = get(s, operation + "?" + query);
                    assertThat(refused.statusCode()).as(operation + "?" + query).isEqualTo(422);
                    assertThat(node(refused).get("code").asString()).as(operation + "?" + query).isEqualTo("VALIDATION_FAILED");
                }
            }
            for (String query : List.of("sort=net", "direction=up"))
                assertThat(get(s, "/api/sales/revenue/branches?" + one + "&" + query).statusCode()).as(query).isEqualTo(422);
            for (String query : List.of("entryType=REFUND", "pageSize=101", "pageSize=0", "page=0", "direction=up", "page=x"))
                assertThat(get(s, "/api/sales/ledger?" + one + "&" + query).statusCode()).as(query).isEqualTo(422);
        }
    }

    @Test void oneBranchWithoutSalesReadRefusesTheWholeRequest() throws Exception {
        workedExample();
        try (var s = login(firstOnly)) {
            for (String operation : OPERATIONS) {
                for (String query : List.of(branchIds(first, second), branchIds(second), branchIds(first, 999_999_999_999L))) {
                    var refused = get(s, operation + "?" + query + "&" + november());
                    assertThat(refused.statusCode()).as(operation + query).isEqualTo(403);
                    assertThat(node(refused).get("code").asString()).isEqualTo("FORBIDDEN");
                    assertThat(refused.body()).doesNotContain("approvedAmountKrw", "aggregation", "items", "53000", "37000");
                }
                // Omitted: only the branches with sales:read, reported as such.
                var mine = ok(s, operation + "?" + november());
                assertThat(mine.at("/aggregation/condition/branchIds").toString()).isEqualTo("[\"" + first + "\"]");
                assertThat(mine.at("/aggregation/condition/allBranches").asBoolean()).isTrue();
            }
            amounts(ok(s, "/api/sales/revenue?" + november()).get("totals"), 53_000, 16_000, 37_000, 4, 2, "hasData");
            amounts(ok(s, "/api/sales/revenue?" + december()).get("totals"), 0, 12_000, -12_000, 0, 1, "hasData");
            assertThat(ok(s, "/api/sales/revenue/branches?" + december()).get("items")).hasSize(1);
            assertThat(ok(s, "/api/sales/ledger?" + december()).toString()).doesNotContain("payment-" + p7 + "\"");
        }
        try (var s = login(nobody)) {
            for (String operation : OPERATIONS) {
                assertThat(get(s, operation + "?" + november()).statusCode()).as(operation).isEqualTo(403);
                assertThat(get(s, operation + "?" + branchIds(first) + "&" + november()).statusCode()).as(operation).isEqualTo(403);
            }
        }
        try (var s = login(reader)) {
            var both = ok(s, "/api/sales/revenue?" + branchIds(second, first) + "&" + november());
            // The applied branches come back in ID order, whatever order they were sent in.
            assertThat(both.at("/aggregation/condition/branchIds").toString())
                    .isEqualTo("[\"" + Math.min(first, second) + "\",\"" + Math.max(first, second) + "\"]");
            assertThat(both.at("/aggregation/condition/allBranches").asBoolean()).isFalse();
        }
        // A stopped branch keeps its past sales readable (READ_SALES does not need an operating branch).
        jdbc.update("UPDATE branches SET status='SUSPENDED' WHERE id=?", first);
        try (var s = login(reader)) {
            amounts(ok(s, "/api/sales/revenue?" + branchIds(first) + "&" + november()).get("totals"), 53_000, 16_000, 37_000, 4, 2, "hasData");
        }
    }

    @Test void theOwnerReadsEveryBranch() throws Exception {
        workedExample();
        try (var s = login(owner)) {
            amounts(ok(s, "/api/sales/revenue?" + branchIds(first, second) + "&" + november()).get("totals"),
                    53_000, 16_000, 37_000, 4, 2, "hasData");
            var all = ok(s, "/api/sales/revenue/branches?" + november() + "&sort=requested");
            assertThat(all.at("/aggregation/condition/allBranches").asBoolean()).isTrue();
            assertThat(all.at("/aggregation/condition/branchIds").toString()).contains("\"" + first + "\"", "\"" + second + "\"");
            assertThat(get(s, "/api/sales/revenue?" + branchIds(999_999_999_999L) + "&" + november()).statusCode()).isEqualTo(403);
        }
    }

    @Test void m10CallsTheSameServiceWithTheConditionObjectAndGetsTheSameAnswer() throws Exception {
        workedExample();
        Instant from = seoul("2026-11-01T00:00"), to = seoul("2026-12-01T00:00");
        var condition = AggregationCondition.revenue(List.of(first, second), from, to, RefundBasis.PAYMENT_DATE, null);
        try (var s = login(reader)) {
            String query = branchIds(first, second) + "&" + november() + "&refundBasis=paymentDate";
            assertThat(tree(service.revenue(reader, condition))).isEqualTo(ok(s, "/api/sales/revenue?" + query));
            assertThat(tree(service.compareBranches(reader, condition, BranchRevenueSort.REQUESTED, ListQuery.Direction.DESC)))
                    .isEqualTo(ok(s, "/api/sales/revenue/branches?" + query + "&sort=requested"));
            var page = ListQuery.parse(1, 20, null, null, null, java.util.Set.of("basisAt"), "basisAt");
            assertThat(tree(service.ledger(reader, condition, null, page))).isEqualTo(ok(s, "/api/sales/ledger?" + query));
            // The applied condition of a response can be sent back as the next condition.
            var applied = service.revenue(reader, condition).aggregation().condition();
            var again = new AggregationCondition(applied.branchIds().stream().map(Long::valueOf).toList(), applied.from(), applied.to(),
                    RefundBasis.fromWire(applied.refundBasis()), null, null, null, null);
            assertThat(tree(service.revenue(reader, again).totals())).isEqualTo(ok(s, "/api/sales/revenue?" + query).get("totals"));
        }
        // No HTTP, same refusals: a branch without sales:read, an unknown branch, a too long period, foreign filters.
        assertThatThrownBy(() -> service.revenue(firstOnly, AggregationCondition.revenue(List.of(first, second), from, to, null, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.ledger(firstOnly, AggregationCondition.revenue(List.of(second), from, to, null, null), null,
                ListQuery.parse(1, 20, null, null, null, java.util.Set.of("basisAt"), "basisAt"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.compareBranches(nobody, AggregationCondition.revenue(null, from, to, null, null),
                BranchRevenueSort.NET_AMOUNT, ListQuery.Direction.DESC)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.revenue(reader, AggregationCondition.revenue(List.of(first), from,
                from.plusSeconds(31_622_401), null, null))).isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.error("x").code()).isEqualTo("PERIOD_TOO_LONG"));
        assertThatThrownBy(() -> service.revenue(reader, AggregationCondition.revenue(List.of(), from, to, null, null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.error("x").code()).isEqualTo("VALIDATION_FAILED"));
        assertThatThrownBy(() -> service.revenue(reader, new AggregationCondition(List.of(first), from, to, null, null, null, null,
                AggregationGranularity.DAY))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.revenue(reader, new AggregationCondition(List.of(first), from, to, null, null, 1L, null, null)))
                .isInstanceOf(ApiException.class);
        // #88's pass operations take product and category but never a purpose.
        assertThatThrownBy(() -> conditions.resolve(reader, new AggregationCondition(List.of(first), from, to, null, SalesPurpose.PASS,
                null, null, null), AggregationConditions.Target.PASSES, false)).isInstanceOf(ApiException.class);
        assertThat(service.revenue(reader, AggregationCondition.revenue(List.of(first), from, to, null, SalesPurpose.ROOM))
                .totals().netAmountKrw()).isZero();
    }

    @Test void everyResponseCarriesExactlyTheContractFields() throws Exception {
        workedExample();
        var spec = json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("docs/design/foundation/openapi.json")));
        try (var s = login(reader)) {
            String query = branchIds(first, second) + "&" + november();
            var revenue = ok(s, "/api/sales/revenue?" + query);
            var comparison = ok(s, "/api/sales/revenue/branches?" + query);
            var ledger = ok(s, "/api/sales/ledger?" + query);
            same(spec, "RevenueSummary", revenue);
            same(spec, "RevenueAmounts", revenue.get("totals"));
            same(spec, "PurposeRevenue", revenue.at("/byPurpose/0"));
            same(spec, "BranchRevenueComparison", comparison);
            same(spec, "BranchRevenue", comparison.at("/items/0"));
            same(spec, "SalesLedgerPage", ledger);
            same(spec, "PageMeta", ledger.get("meta"));
            same(spec, "SalesLedgerEntry", ledger.at("/items/0"));
            for (var body : List.of(revenue, comparison, ledger)) {
                same(spec, "AggregationMeta", body.get("aggregation"));
                same(spec, "AggregationCondition", body.at("/aggregation/condition"));
                same(spec, "AggregationPartial", body.at("/aggregation/partial"));
                same(spec, "MetricRef", body.at("/aggregation/metrics/0"));
                assertThat(body.at("/aggregation/metrics")).hasSize(5);
            }
        }
    }

    void same(JsonNode spec, String schema, JsonNode actual) {
        var expected = new java.util.ArrayList<String>();
        spec.at("/components/schemas/" + schema + "/properties").properties().forEach(entry -> expected.add(entry.getKey()));
        assertThat(fields(actual)).as(schema).containsExactlyInAnyOrderElementsOf(expected);
    }

    JsonNode tree(Object value) { return json.readTree(json.writeValueAsString(value)); }
}
