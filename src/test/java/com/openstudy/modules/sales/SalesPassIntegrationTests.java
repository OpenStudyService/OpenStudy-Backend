package com.openstudy.modules.sales;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #88, pass volume and the product ranking: the worked example's pass table for both refund bases, payment count,
 * net sold quantity and issued count kept apart (M9-D06), sale-time snapshots across a rename and a category move, a
 * stable tie order with competition ranks across pages, and both totals equal to the revenue and ledger of the same
 * condition.
 */
class SalesPassIntegrationTests extends SalesIntegrationSupport {

    @Test void theWorkedExamplePassTableIsReproducedForBothRefundBases() throws Exception {
        workedExample();
        try (var s = login(reader)) {
            String one = branchIds(first) + "&";
            String volume = "/api/sales/passes/volume?";
            volume(ok(s, volume + one + november()).get("totals"), 2, 2, 1, 1, 2, "hasData");
            volume(ok(s, volume + one + november() + "&refundBasis=paymentDate").get("totals"), 2, 2, 1, 1, 2, "hasData");
            volume(ok(s, volume + one + october()).get("totals"), 1, 1, 0, 1, 1, "hasData");
            // A refunded sale leaves the net sold quantity and stays in the issued count.
            volume(ok(s, volume + one + october() + "&refundBasis=paymentDate").get("totals"), 1, 1, 1, 0, 1, "hasData");
            // An earlier sale refunded in this period: negative, not clamped.
            volume(ok(s, volume + one + december()).get("totals"), 0, 0, 1, -1, 0, "hasData");
            volume(ok(s, volume + one + december() + "&refundBasis=paymentDate").get("totals"), 0, 0, 0, 0, 0, "noData");
            // Branch 2 processed P6's refund, but P6 belongs to branch 1; branch 2 only has P7.
            volume(ok(s, volume + branchIds(second) + "&" + november()).get("totals"), 0, 0, 0, 0, 0, "noData");
            volume(ok(s, volume + branchIds(second) + "&" + december()).get("totals"), 1, 1, 0, 1, 1, "hasData");

            var body = ok(s, volume + one + november());
            var meta = body.get("aggregation");
            assertThat(meta.get("rowCount").asLong()).isEqualTo(1);
            // Two pass approvals (P3, P6) and one pass refund (P1); P2's room refund and the failed P4 are not pass items.
            assertThat(meta.get("sourceRowCount").asLong()).isEqualTo(3);
            assertThat(meta.at("/condition/purpose").asString()).isEqualTo("이용권");
            assertThat(meta.at("/condition/granularity").isNull()).isTrue();
            assertThat(meta.at("/partial/firstBucketPartial").isNull()).isTrue();
            assertThat(meta.get("metrics").toString()).contains("\"pass.payment_count\"", "\"건\"", "\"pass.issued_count\"", "\"개\"")
                    .doesNotContain("sales.");
            assertThat(meta.get("metrics")).hasSize(5);
        }
    }

    @Test void passNumbersMatchTheRevenueAndTheLedgerOfTheSameCondition() throws Exception {
        workedExample();
        try (var s = login(reader)) {
            for (String period : List.of(october(), november(), december())) {
                for (String basis : List.of("processedDate", "paymentDate")) {
                    String query = branchIds(first, second) + "&" + period + "&refundBasis=" + basis;
                    var totals = ok(s, "/api/sales/passes/volume?" + query).get("totals");
                    var revenue = ok(s, "/api/sales/revenue?" + query + "&purpose=" + enc("이용권")).get("totals");
                    var ledger = ok(s, "/api/sales/ledger?" + query + "&purpose=" + enc("이용권") + "&pageSize=100");
                    assertThat(totals.get("paymentCount").asLong()).as(query).isEqualTo(revenue.get("approvedPaymentCount").asLong());
                    long sold = 0, refunded = 0;
                    for (var item : ledger.get("items")) {
                        long quantity = item.get("passQuantity").asLong();
                        if (quantity > 0) sold += quantity; else refunded -= quantity;
                    }
                    assertThat(totals.get("soldQuantity").asLong()).as(query).isEqualTo(sold);
                    assertThat(totals.get("refundedQuantity").asLong()).as(query).isEqualTo(refunded);
                    // V1 has no pass without a payment, so the issued count equals the sold quantity.
                    assertThat(totals.get("issuedCount").asLong()).as(query).isEqualTo(sold);
                    var products = ok(s, "/api/sales/passes/products?" + query);
                    assertThat(products.get("totals")).as(query).isEqualTo(totals);
                    assertThat(products.at("/aggregation/sourceRowCount").asLong()).as(query)
                            .isEqualTo(ledger.at("/meta/total").asLong());
                }
            }
        }
    }

    @Test void productRowsFollowTheWorkedExampleAndShareRanksOnTies() throws Exception {
        workedExample();
        try (var s = login(reader)) {
            var body = ok(s, "/api/sales/passes/products?" + branchIds(first) + "&" + november());
            var items = body.get("items");
            // P3 (1만5천) and P6 (1만2천) sold one each, P1 (1만) was only refunded: 1, 1, -1 -> ranks 1, 1, 3.
            assertThat(names(items)).containsExactly(token + " 1만5천", token + " 1만2천", token + " 1만");
            assertThat(ranks(items)).containsExactly(1L, 1L, 3L);
            volume(items.get(0), 1, 1, 0, 1, 1, "hasData");
            volume(items.get(1), 1, 1, 0, 1, 1, "hasData");
            volume(items.get(2), 0, 0, 1, -1, 0, "hasData");
            assertThat(items.get(0).get("kind").asString()).isEqualTo("시간권");
            assertThat(items.get(0).get("categoryCode").asString()).isEqualTo(categoryCode());
            assertThat(items.get(0).get("categoryName").asString()).isEqualTo("매출 분류");
            volume(body.get("totals"), 2, 2, 1, 1, 2, "hasData");
            assertThat(body.at("/meta/total").asLong()).isEqualTo(3);
            assertThat(body.at("/aggregation/rowCount").asLong()).isEqualTo(3);
            assertThat(body.at("/aggregation/sourceRowCount").asLong()).isEqualTo(3);
            // A product without a sale or refund in the period has no row, and an empty period is an empty list.
            assertThat(body.toString()).doesNotContain(token + " 9천");
            var empty = ok(s, "/api/sales/passes/products?" + branchIds(second) + "&" + november());
            assertThat(empty.get("items")).isEmpty();
            assertThat(empty.at("/meta/total").asLong()).isZero();
            assertThat(empty.at("/aggregation/dataStatus").asString()).isEqualTo("noData");
            volume(empty.get("totals"), 0, 0, 0, 0, 0, "noData");
        }
    }

    @Test void aRenamedOrMovedProductKeepsItsEarlierSalesUnderTheSaleTimeSnapshot() throws Exception {
        long product = product("Pass", 10_000);
        String other = category();
        String otherCode = jdbc.queryForObject("SELECT code FROM product_categories WHERE id=?", String.class, other);
        sold(first, product, seoul("2026-11-03T10:00"), true);
        sold(first, product, seoul("2026-11-03T11:00"), true);
        // A change of case only: under the tables' case-insensitive collation it would fold into the old row.
        rename(product, 1, "PASS", null);
        sold(first, product, seoul("2026-11-04T10:00"), true);
        rename(product, 2, "새 이름", other);
        long renamedSale = sold(first, product, seoul("2026-11-05T10:00"), true);
        refunded(renamedSale, seoul("2026-11-06T10:00"));
        clock.fixed = AS_OF;
        try (var s = login(reader)) {
            String query = branchIds(first) + "&" + november();
            var items = ok(s, "/api/sales/passes/products?" + query).get("items");
            assertThat(names(items)).containsExactly(token + " Pass", token + " PASS", token + " 새 이름");
            assertThat(ranks(items)).containsExactly(1L, 2L, 3L);
            for (var item : items) assertThat(item.get("productId").asString()).isEqualTo(Long.toString(product));
            volume(items.get(0), 2, 2, 0, 2, 2, "hasData");
            volume(items.get(1), 1, 1, 0, 1, 1, "hasData");
            // The refund leaves the row of the snapshot it was sold under.
            volume(items.get(2), 1, 1, 1, 0, 1, "hasData");
            assertThat(items.get(0).get("categoryCode").asString()).isEqualTo(categoryCode());
            assertThat(items.get(2).get("categoryCode").asString()).isEqualTo(otherCode);

            // productId takes every name of the product; categoryCode is the exact sale-time code.
            assertThat(ok(s, "/api/sales/passes/products?" + query + "&productId=" + product).get("items")).hasSize(3);
            var moved = ok(s, "/api/sales/passes/products?" + query + "&categoryCode=" + otherCode);
            assertThat(names(moved.get("items"))).containsExactly(token + " 새 이름");
            assertThat(moved.at("/aggregation/condition/categoryCode").asString()).isEqualTo(otherCode);
            volume(ok(s, "/api/sales/passes/volume?" + query + "&categoryCode=" + categoryCode()).get("totals"), 3, 3, 0, 3, 3, "hasData");
            volume(ok(s, "/api/sales/passes/volume?" + query + "&categoryCode=" + otherCode.toLowerCase()).get("totals"),
                    0, 0, 0, 0, 0, "noData");
            var byProduct = ok(s, "/api/sales/passes/volume?" + query + "&productId=" + product);
            volume(byProduct.get("totals"), 4, 4, 1, 3, 4, "hasData");
            assertThat(byProduct.at("/aggregation/condition/productId").asString()).isEqualTo(Long.toString(product));
            volume(ok(s, "/api/sales/passes/volume?" + query + "&productId=999999999999").get("totals"), 0, 0, 0, 0, 0, "noData");
        }
    }

    @Test void tiesAreOrderedTheSameWayEveryTimeAndRanksHoldAcrossPages() throws Exception {
        long a = product("가", 1_000), b = product("나", 1_000), c = product("다", 1_000), d = product("라", 1_000);
        // Net sold 2, 1, 1, 0 -> ranks 1, 2, 2, 4. b and c tie and are ordered by productId.
        sold(first, a, seoul("2026-11-10T09:00"), true);
        sold(first, a, seoul("2026-11-10T09:01"), true);
        sold(first, c, seoul("2026-11-10T09:02"), true);
        sold(first, b, seoul("2026-11-10T09:03"), true);
        refunded(sold(first, d, seoul("2026-11-10T09:04"), true), seoul("2026-11-10T09:05"));
        clock.fixed = AS_OF;
        try (var s = login(reader)) {
            String base = "/api/sales/passes/products?" + branchIds(first) + "&" + november();
            var all = ok(s, base).get("items");
            assertThat(names(all)).containsExactly(token + " 가", token + " 나", token + " 다", token + " 라");
            assertThat(ranks(all)).containsExactly(1L, 2L, 2L, 4L);
            for (int round = 0; round < 3; round++) assertThat(ok(s, base).get("items")).isEqualTo(all);
            var second = ok(s, base + "&page=2&pageSize=2");
            assertThat(names(second.get("items"))).containsExactly(token + " 다", token + " 라");
            assertThat(ranks(second.get("items"))).containsExactly(2L, 4L);
            assertThat(second.at("/meta/total").asLong()).isEqualTo(4);
            assertThat(second.at("/meta/page").asLong()).isEqualTo(2);
            volume(second.get("totals"), 5, 5, 1, 4, 5, "hasData");
            var beyond = ok(s, base + "&page=3&pageSize=2");
            assertThat(beyond.get("items")).isEmpty();
            assertThat(beyond.at("/meta/total").asLong()).isEqualTo(4);
            for (String query : List.of("sort=rank", "direction=asc"))
                assertThat(ok(s, base + "&" + query).get("items")).as(query).isEqualTo(all);
        }
        assertThat(a).isLessThan(b);
    }

    @Test void passOperationsRefuseAPurposeAndRevenueOperationsRefuseProductFilters() throws Exception {
        try (var s = login(reader)) {
            String one = branchIds(first) + "&" + november();
            for (String operation : List.of("/api/sales/passes/volume", "/api/sales/passes/products")) {
                for (String query : List.of("purpose=" + enc("이용권"), "purpose=" + enc("스터디룸"), "productId=0", "productId=x",
                        "categoryCode=", "categoryCode=" + "C".repeat(41), "refundBasis=processed", "branchIds=" + first)) {
                    var refused = get(s, operation + "?" + one + "&" + query);
                    assertThat(refused.statusCode()).as(operation + "?" + query).isEqualTo(422);
                    assertThat(node(refused).get("code").asString()).as(query).isEqualTo("VALIDATION_FAILED");
                }
                var tooLong = get(s, operation + "?" + branchIds(first) + "&" + period("2026-01-01T00:00", "2027-01-02T00:00:01"));
                assertThat(tooLong.statusCode()).isEqualTo(422);
                assertThat(node(tooLong).get("code").asString()).isEqualTo("PERIOD_TOO_LONG");
            }
            for (String query : List.of("pageSize=101", "pageSize=0", "page=0"))
                assertThat(get(s, "/api/sales/passes/products?" + one + "&" + query).statusCode()).as(query).isEqualTo(422);
        }
    }

    @Test void oneBranchWithoutSalesReadRefusesThePassOperations() throws Exception {
        workedExample();
        try (var s = login(firstOnly)) {
            for (String operation : List.of("/api/sales/passes/volume", "/api/sales/passes/products")) {
                for (String query : List.of(branchIds(first, second), branchIds(second), branchIds(first, 999_999_999_999L))) {
                    var refused = get(s, operation + "?" + query + "&" + november());
                    assertThat(refused.statusCode()).as(operation + query).isEqualTo(403);
                    assertThat(node(refused).get("code").asString()).isEqualTo("FORBIDDEN");
                    assertThat(refused.body()).doesNotContain("aggregation", "items", "paymentCount");
                }
                var mine = ok(s, operation + "?" + december());
                assertThat(mine.at("/aggregation/condition/branchIds").toString()).isEqualTo("[\"" + first + "\"]");
                assertThat(mine.at("/aggregation/condition/allBranches").asBoolean()).isTrue();
                // P7 is branch 2's: only P6's refund is visible.
                volume(mine.get("totals"), 0, 0, 1, -1, 0, "hasData");
            }
        }
        try (var s = login(nobody)) {
            for (String operation : List.of("/api/sales/passes/volume", "/api/sales/passes/products"))
                assertThat(get(s, operation + "?" + november()).statusCode()).as(operation).isEqualTo(403);
        }
    }

    static List<String> names(JsonNode items) {
        var names = new ArrayList<String>();
        for (var item : items) names.add(item.get("productName").asString());
        return names;
    }

    static List<Long> ranks(JsonNode items) {
        var ranks = new ArrayList<Long>();
        for (var item : items) ranks.add(item.get("rank").asLong());
        return ranks;
    }
}
