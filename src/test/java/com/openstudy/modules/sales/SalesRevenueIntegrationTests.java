package com.openstudy.modules.sales;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #87, revenue and branch comparison: the worked example of aggregation-api.md "계산 예시" reproduced number for
 * number, both refund bases across a period boundary, a refund processed at another branch, every purpose with its
 * subtotal and filter, the comparison orders with zero rows, and zero apart from no data (M9-D02·D03·D05·D09).
 */
class SalesRevenueIntegrationTests extends SalesIntegrationSupport {

    JsonNode revenue(Sessionish who, String query) throws Exception { return who.get("/api/sales/revenue?" + query); }

    interface Sessionish { JsonNode get(String path) throws Exception; }

    @Test void theWorkedExampleTableIsReproducedForBothRefundBases() throws Exception {
        workedExample();
        try (var s = login(reader)) {
            Sessionish r = path -> ok(s, path);
            String one = branchIds(first), two = branchIds(second);
            amounts(revenue(r, one + "&" + november()).get("totals"), 53_000, 16_000, 37_000, 4, 2, "hasData");
            amounts(revenue(r, one + "&" + november() + "&refundBasis=paymentDate").get("totals"), 53_000, 18_000, 35_000, 4, 2, "hasData");
            for (String basis : List.of("processedDate", "paymentDate")) {
                amounts(revenue(r, two + "&" + november() + "&refundBasis=" + basis).get("totals"), 0, 0, 0, 0, 0, "noData");
                amounts(revenue(r, two + "&" + december() + "&refundBasis=" + basis).get("totals"), 9_000, 0, 9_000, 1, 0, "hasData");
            }
            amounts(revenue(r, one + "&" + december()).get("totals"), 0, 12_000, -12_000, 0, 1, "hasData");
            amounts(revenue(r, one + "&" + december() + "&refundBasis=paymentDate").get("totals"), 0, 0, 0, 0, 0, "noData");
            amounts(revenue(r, one + "&" + october()).get("totals"), 10_000, 0, 10_000, 1, 0, "hasData");
            amounts(revenue(r, one + "&" + october() + "&refundBasis=paymentDate").get("totals"), 10_000, 10_000, 0, 1, 1, "hasData");

            var processed = revenue(r, one + "&" + november());
            var byPurpose = processed.get("byPurpose");
            assertThat(byPurpose).hasSize(3);
            assertThat(byPurpose.get(0).get("purpose").asString()).isEqualTo("이용권");
            amounts(byPurpose.get(0), 27_000, 10_000, 17_000, 2, 1, "hasData");
            assertThat(byPurpose.get(1).get("purpose").asString()).isEqualTo("스터디룸");
            amounts(byPurpose.get(1), 6_000, 6_000, 0, 1, 1, "hasData");
            assertThat(byPurpose.get(2).get("purpose").asString()).isEqualTo("사물함");
            amounts(byPurpose.get(2), 20_000, 0, 20_000, 1, 0, "hasData");
            var paid = revenue(r, one + "&" + november() + "&refundBasis=paymentDate").get("byPurpose");
            amounts(paid.get(0), 27_000, 12_000, 15_000, 2, 1, "hasData");
            amounts(paid.get(1), 6_000, 6_000, 0, 1, 1, "hasData");
            amounts(paid.get(2), 20_000, 0, 20_000, 1, 0, "hasData");

            var meta = processed.get("aggregation");
            assertThat(meta.get("rowCount").asLong()).isEqualTo(3);
            assertThat(meta.get("sourceRowCount").asLong()).isEqualTo(6);
            assertThat(meta.get("dataStatus").asString()).isEqualTo("hasData");
            assertThat(meta.at("/condition/refundBasis").asString()).isEqualTo("processedDate");
            assertThat(meta.at("/condition/purpose").isNull()).isTrue();
            assertThat(meta.get("metrics").toString()).contains("sales.approved_amount", "sales.refund_count", "\"원\"", "\"건\"");
        }
    }

    @Test void eachPurposeFiltersToOneRowAndTheTotalsAreThatRow() throws Exception {
        workedExample();
        try (var s = login(reader)) {
            Sessionish r = path -> ok(s, path);
            String one = branchIds(first) + "&" + november();
            long[][] expected = {{27_000, 10_000, 17_000, 2, 1}, {6_000, 6_000, 0, 1, 1}, {20_000, 0, 20_000, 1, 0}};
            String[] labels = {"이용권", "스터디룸", "사물함"};
            for (int index = 0; index < labels.length; index++) {
                var body = revenue(r, one + "&purpose=" + enc(labels[index]));
                assertThat(body.get("byPurpose")).hasSize(1);
                assertThat(body.at("/byPurpose/0/purpose").asString()).isEqualTo(labels[index]);
                long[] e = expected[index];
                amounts(body.get("totals"), e[0], e[1], e[2], e[3], e[4], "hasData");
                amounts(body.at("/byPurpose/0"), e[0], e[1], e[2], e[3], e[4], "hasData");
                assertThat(body.at("/aggregation/condition/purpose").asString()).isEqualTo(labels[index]);
                assertThat(body.at("/aggregation/rowCount").asLong()).isEqualTo(1);
                assertThat(body.at("/aggregation/sourceRowCount").asLong()).isEqualTo(e[3] + e[4]);
            }
        }
    }

    @Test void theFailedPaymentAndPassConsumptionAreNotRevenue() throws Exception {
        workedExample();
        long pass = jdbc.queryForObject("SELECT id FROM passes WHERE purchase_payment_id=?", Long.class, p3);
        try (var s = login(reader)) {
            var before = ok(s, "/api/sales/revenue?" + branchIds(first) + "&" + november());
            // An adjustment through the real API writes an ADJUST pass_balance_events row (pass consumption).
            try (var payer = login(seller)) {
                long version = jdbc.queryForObject("SELECT version FROM passes WHERE id=?", Long.class, pass) + 1;
                var adjusted = http(payer, "POST", "/api/passes/" + pass + "/adjustments",
                        java.util.Map.of("version", version, "reason", "사용 보정", "remainingMinutes", 300L), key());
                assertThat(adjusted.statusCode()).as(adjusted.body()).isEqualTo(200);
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pass_balance_events WHERE pass_id=? AND kind='ADJUST'", Long.class, pass)).isEqualTo(1);
            var after = ok(s, "/api/sales/revenue?" + branchIds(first) + "&" + november());
            assertThat(after.get("totals")).isEqualTo(before.get("totals"));
            assertThat(after.get("byPurpose")).isEqualTo(before.get("byPurpose"));
        }
        // P4 is stored as a failed payment of 15,000 inside November, and still nowhere in the numbers above.
        var failed = jdbc.queryForMap("SELECT status, approved_at, failed_at FROM payments WHERE id=?", p4);
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("approved_at")).isNull();
        assertThat(failed.get("failed_at")).isNotNull();
    }

    @Test void storedRowsMatchTheMetricDefinitions() throws Exception {
        workedExample();
        // Refunds keep the payment's amount and approval time and only flip its status (FR-PAY-10).
        for (long payment : List.of(p1, p2, p6)) {
            var row = jdbc.queryForMap("SELECT p.status, p.amount_krw pa, r.amount_krw ra, p.branch_id pb, r.processing_branch_id rb "
                    + "FROM payments p JOIN refunds r ON r.payment_id=p.id WHERE p.id=?", payment);
            assertThat(row.get("status")).isEqualTo("REFUNDED");
            assertThat(((Number) row.get("pa")).longValue()).isEqualTo(((Number) row.get("ra")).longValue());
            assertThat(((Number) row.get("pb")).longValue()).isEqualTo(first);
        }
        assertThat(jdbc.queryForObject("SELECT processing_branch_id FROM refunds WHERE id=?", Long.class, r6)).isEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT processing_branch_id FROM refunds WHERE id=?", Long.class, r1)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(approved_at,'%Y-%m-%dT%H:%i:%sZ') FROM payments WHERE id=?", String.class, p2))
                .isEqualTo("2026-10-31T15:30:00Z");
        assertThat(jdbc.queryForObject("SELECT DATE_FORMAT(refunded_at,'%Y-%m-%dT%H:%i:%sZ') FROM refunds WHERE id=?", String.class, r1))
                .isEqualTo("2026-11-02T01:00:00Z");
    }

    @Test void aRefundIsDeductedAtTheOriginalBranchNotWhereItWasProcessed() throws Exception {
        workedExample();
        try (var s = login(reader)) {
            // P6 was refunded at branch 2 on 12-02: branch 1 loses it, branch 2 never sees it.
            amounts(ok(s, "/api/sales/revenue?" + branchIds(first) + "&" + december()).get("totals"), 0, 12_000, -12_000, 0, 1, "hasData");
            amounts(ok(s, "/api/sales/revenue?" + branchIds(second) + "&" + december()).get("totals"), 9_000, 0, 9_000, 1, 0, "hasData");
            var both = ok(s, "/api/sales/revenue/branches?" + branchIds(first, second) + "&" + december() + "&sort=branchId&direction=asc");
            amounts(both.at("/items/0"), 0, 12_000, -12_000, 0, 1, "hasData");
            amounts(both.at("/items/1"), 9_000, 0, 9_000, 1, 0, "hasData");
        }
        try (var s = login(firstOnly)) {
            // A reader of branch 1 alone still sees the refund processed at branch 2, attributed to branch 1.
            amounts(ok(s, "/api/sales/revenue?" + branchIds(first) + "&" + december()).get("totals"), 0, 12_000, -12_000, 0, 1, "hasData");
        }
    }

    @Test void theBranchComparisonKeepsTheRequestedOrderAndZeroRows() throws Exception {
        workedExample();
        long third = branch("매출 셋째 지점");
        member(reader, third, "sales:read");
        try (var s = login(reader)) {
            var net = ok(s, "/api/sales/revenue/branches?" + branchIds(first, second) + "&" + november());
            assertThat(net.get("items")).hasSize(2);
            assertThat(net.at("/items/0/branchId").asString()).isEqualTo(Long.toString(first));
            assertThat(net.at("/items/0/branchName").asString()).isEqualTo("매출 첫 지점");
            amounts(net.at("/items/0"), 53_000, 16_000, 37_000, 4, 2, "hasData");
            assertThat(net.at("/items/1/branchId").asString()).isEqualTo(Long.toString(second));
            amounts(net.at("/items/1"), 0, 0, 0, 0, 0, "noData");
            assertThat(net.at("/items/1/byPurpose")).hasSize(3);
            for (var row : net.at("/items/1/byPurpose")) amounts(row, 0, 0, 0, 0, 0, "noData");
            amounts(net.get("totals"), 53_000, 16_000, 37_000, 4, 2, "hasData");
            assertThat(net.at("/aggregation/rowCount").asLong()).isEqualTo(2);
            assertThat(net.at("/aggregation/sourceRowCount").asLong()).isEqualTo(6);
            var revenue = ok(s, "/api/sales/revenue?" + branchIds(first, second) + "&" + november());
            assertThat(net.get("totals")).isEqualTo(revenue.get("totals"));

            // December: branch 1 −12,000, branch 2 +9,000, branch 3 nothing.
            String dec = branchIds(third, first, second) + "&" + december();
            assertThat(order(ok(s, "/api/sales/revenue/branches?" + dec))).containsExactly(second, third, first);
            assertThat(order(ok(s, "/api/sales/revenue/branches?" + dec + "&direction=asc"))).containsExactly(first, third, second);
            assertThat(order(ok(s, "/api/sales/revenue/branches?" + dec + "&sort=requested"))).containsExactly(third, first, second);
            assertThat(order(ok(s, "/api/sales/revenue/branches?" + dec + "&sort=requested&direction=asc"))).containsExactly(third, first, second);
            assertThat(order(ok(s, "/api/sales/revenue/branches?" + dec + "&sort=approvedAmount"))).containsExactly(second, third, first);
            assertThat(order(ok(s, "/api/sales/revenue/branches?" + dec + "&sort=refundAmount"))).containsExactly(first, third, second);
            assertThat(order(ok(s, "/api/sales/revenue/branches?" + dec + "&sort=branchId"))).containsExactly(third, second, first);
            assertThat(order(ok(s, "/api/sales/revenue/branches?" + dec + "&sort=branchId&direction=asc"))).containsExactly(first, second, third);
            // Equal amounts fall back to branchId in the same direction: November, branches 2 and 3 are both 0.
            String tie = branchIds(third, second) + "&" + november();
            assertThat(order(ok(s, "/api/sales/revenue/branches?" + tie))).containsExactly(third, second);
            assertThat(order(ok(s, "/api/sales/revenue/branches?" + tie + "&direction=asc"))).containsExactly(second, third);
            var zero = ok(s, "/api/sales/revenue/branches?" + tie);
            for (var row : zero.get("items")) amounts(row, 0, 0, 0, 0, 0, "noData");
            assertThat(zero.at("/aggregation/dataStatus").asString()).isEqualTo("noData");
            // Omitted branchIds: every sales:read branch, requested order = ID ascending.
            var all = ok(s, "/api/sales/revenue/branches?" + december() + "&sort=requested");
            assertThat(order(all)).containsExactly(first, second, third);
            assertThat(all.at("/aggregation/condition/allBranches").asBoolean()).isTrue();
            // A purpose filter applies to each row and its subtotal.
            var rooms = ok(s, "/api/sales/revenue/branches?" + branchIds(first, second) + "&" + november() + "&purpose=" + enc("스터디룸")
                    + "&sort=branchId&direction=asc");
            amounts(rooms.at("/items/0"), 6_000, 6_000, 0, 1, 1, "hasData");
            assertThat(rooms.at("/items/0/byPurpose")).hasSize(1);
        }
    }

    @Test void zeroWithDataIsNotNoData() throws Exception {
        workedExample();
        try (var s = login(reader)) {
            // October by payment date: 10,000 approved and the same refunded → 0 that has data.
            var zero = ok(s, "/api/sales/revenue?" + branchIds(first) + "&" + october() + "&refundBasis=paymentDate");
            amounts(zero.get("totals"), 10_000, 10_000, 0, 1, 1, "hasData");
            assertThat(zero.at("/aggregation/dataStatus").asString()).isEqualTo("hasData");
            amounts(zero.at("/byPurpose/1"), 0, 0, 0, 0, 0, "noData");
            var none = ok(s, "/api/sales/revenue?" + branchIds(first) + "&" + period("2025-01-01T00:00", "2025-02-01T00:00"));
            amounts(none.get("totals"), 0, 0, 0, 0, 0, "noData");
            assertThat(none.at("/aggregation/dataStatus").asString()).isEqualTo("noData");
            assertThat(none.at("/aggregation/sourceRowCount").asLong()).isZero();
            assertThat(none.get("byPurpose")).hasSize(3);
        }
    }

    List<Long> order(JsonNode comparison) {
        return java.util.stream.StreamSupport.stream(comparison.get("items").spliterator(), false)
                .map(row -> Long.parseLong(row.get("branchId").asString())).toList();
    }
}
