package com.openstudy.modules.sales;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #87, minimal ledger (FR-AGG-18): exactly the contract's fields and nothing that identifies a customer, the
 * order and pages of the contract, and the consistency rules — the ledger of a condition sums to the revenue of the same
 * condition and its total is the sourceRowCount — checked for every month, basis, purpose and branch of the example.
 */
class SalesLedgerIntegrationTests extends SalesIntegrationSupport {

    @Test void theLedgerOfNovemberIsTheWorkedExample() throws Exception {
        workedExample();
        try (var s = login(reader)) {
            var processed = ok(s, "/api/sales/ledger?" + branchIds(first) + "&" + november());
            assertThat(ids(processed)).containsExactly("payment-" + p3, "payment-" + p6, "payment-" + p5, "refund-" + r2,
                    "refund-" + r1, "payment-" + p2);
            assertThat(sum(processed)).isEqualTo(37_000);
            assertThat(processed.at("/meta/total").asLong()).isEqualTo(6);
            assertThat(processed.at("/aggregation/sourceRowCount").asLong()).isEqualTo(6);
            assertThat(processed.at("/aggregation/rowCount").asLong()).isEqualTo(6);
            amounts(processed.get("totals"), 53_000, 16_000, 37_000, 4, 2, "hasData");
            var refundOfP1 = processed.at("/items/4");
            assertThat(refundOfP1.get("entryType").asString()).isEqualTo("환불");
            assertThat(refundOfP1.get("basisAt").asString()).isEqualTo("2026-11-02T01:00:00Z");
            assertThat(refundOfP1.get("approvedAt").asString()).isEqualTo("2026-10-31T14:30:00Z");
            assertThat(refundOfP1.get("refundedAt").asString()).isEqualTo("2026-11-02T01:00:00Z");
            assertThat(refundOfP1.get("amountKrw").asLong()).isEqualTo(-10_000);
            assertThat(refundOfP1.get("passQuantity").asInt()).isEqualTo(-1);
            assertThat(refundOfP1.get("paymentId").asString()).isEqualTo(Long.toString(p1));
            assertThat(refundOfP1.get("refundId").asString()).isEqualTo(Long.toString(r1));
            assertThat(refundOfP1.get("processingBranchId").asString()).isEqualTo(Long.toString(first));
            assertThat(refundOfP1.get("productName").asString()).isEqualTo(token + " 1만");
            assertThat(refundOfP1.get("categoryCode").asString()).isEqualTo(categoryCode());
            assertThat(refundOfP1.get("categoryName").asString()).isEqualTo("매출 분류");
            var sale = processed.at("/items/0");
            assertThat(sale.get("entryType").asString()).isEqualTo("승인");
            assertThat(sale.get("amountKrw").asLong()).isEqualTo(15_000);
            assertThat(sale.get("passQuantity").asInt()).isEqualTo(1);
            assertThat(sale.get("refundId").isNull()).isTrue();
            assertThat(sale.get("refundedAt").isNull()).isTrue();
            assertThat(sale.get("processingBranchId").isNull()).isTrue();
            assertThat(sale.get("basisAt").asString()).isEqualTo("2026-11-30T14:59:59Z");
            assertThat(sale.get("productId").isNull()).isFalse();
            var locker = processed.at("/items/2");
            assertThat(locker.get("purpose").asString()).isEqualTo("사물함");
            assertThat(locker.get("productId").isNull()).isTrue();
            assertThat(locker.get("productName").isNull()).isTrue();
            assertThat(locker.get("categoryCode").isNull()).isTrue();
            assertThat(locker.get("passQuantity").asInt()).isZero();
            var room = processed.at("/items/5");
            assertThat(room.get("purpose").asString()).isEqualTo("스터디룸");
            assertThat(room.get("basisAt").asString()).isEqualTo("2026-10-31T15:30:00Z");
            assertThat(room.get("productName").isNull()).isTrue();

            var paid = ok(s, "/api/sales/ledger?" + branchIds(first) + "&" + november() + "&refundBasis=paymentDate");
            assertThat(ids(paid)).containsExactly("payment-" + p3, "refund-" + r6, "payment-" + p6, "payment-" + p5,
                    "refund-" + r2, "payment-" + p2);
            assertThat(sum(paid)).isEqualTo(35_000);
            assertThat(paid.at("/meta/total").asLong()).isEqualTo(6);
            var refundOfP6 = paid.at("/items/1");
            assertThat(refundOfP6.get("processingBranchId").asString()).isEqualTo(Long.toString(second));
            assertThat(refundOfP6.get("branchId").asString()).isEqualTo(Long.toString(first));
            assertThat(refundOfP6.get("basisAt").asString()).isEqualTo(refundOfP6.get("approvedAt").asString());
            assertThat(refundOfP6.get("refundedAt").asString()).isEqualTo("2026-12-02T01:00:00Z");
            var ascending = ok(s, "/api/sales/ledger?" + branchIds(first) + "&" + november() + "&refundBasis=paymentDate&direction=asc");
            assertThat(ids(ascending)).containsExactly("payment-" + p2, "refund-" + r2, "payment-" + p5, "payment-" + p6,
                    "refund-" + r6, "payment-" + p3);
            // P4 failed: never an item, in any month.
            for (String month : List.of(october(), november(), december()))
                assertThat(ok(s, "/api/sales/ledger?" + branchIds(first) + "&" + month).toString()).doesNotContain("payment-" + p4 + "\"");
        }
    }

    @Test void theLedgerCarriesExactlyTheContractFieldsAndNoCustomerData() throws Exception {
        workedExample();
        var contract = new ArrayList<String>();
        var spec = json.readTree(Files.readString(Path.of("docs/design/foundation/openapi.json")));
        spec.at("/components/schemas/SalesLedgerEntry/properties").properties().forEach(entry -> contract.add(entry.getKey()));
        try (var s = login(reader)) {
            var body = get(s, "/api/sales/ledger?" + branchIds(first) + "&" + november()).body();
            var page = json.readTree(body);
            assertThat(fields(page)).containsExactly("items", "meta", "aggregation", "totals");
            for (var item : page.get("items")) assertThat(fields(item)).containsExactlyElementsOf(contract);
            // The entries and totals carry no customer data; the applied condition names its (empty) advanced filters (#116).
            assertThat(page.get("items").toString() + page.get("totals") + page.get("meta")).doesNotContain("매출 고객", "01012345678",
                    "010-1234-5678", "고객 요청", "예약 취소", "카드",
                    "customer", "method", "reason", "processedBy", "createdBy", "매출 직원");
        }
    }

    @Test void pagesEntryTypesAndTheReadersPaymentPermission() throws Exception {
        workedExample();
        String base = "/api/sales/ledger?" + branchIds(first) + "&" + november();
        try (var s = login(reader)) {
            var all = ids(ok(s, base));
            var secondPage = ok(s, base + "&page=2&pageSize=4");
            assertThat(ids(secondPage)).containsExactlyElementsOf(all.subList(4, 6));
            assertThat(secondPage.at("/meta/page").asLong()).isEqualTo(2);
            assertThat(secondPage.at("/meta/pageSize").asLong()).isEqualTo(4);
            assertThat(secondPage.at("/meta/total").asLong()).isEqualTo(6);
            // totals are the whole condition, not the page.
            amounts(secondPage.get("totals"), 53_000, 16_000, 37_000, 4, 2, "hasData");
            var beyond = ok(s, base + "&page=3&pageSize=4");
            assertThat(beyond.get("items")).isEmpty();
            assertThat(beyond.at("/meta/total").asLong()).isEqualTo(6);
            var refunds = ok(s, base + "&entryType=" + enc("환불"));
            assertThat(ids(refunds)).containsExactly("refund-" + r2, "refund-" + r1);
            amounts(refunds.get("totals"), 0, 16_000, -16_000, 0, 2, "hasData");
            assertThat(refunds.at("/meta/total").asLong()).isEqualTo(2);
            assertThat(refunds.at("/aggregation/sourceRowCount").asLong()).isEqualTo(2);
            var approvals = ok(s, base + "&entryType=" + enc("승인"));
            assertThat(approvals.at("/meta/total").asLong()).isEqualTo(4);
            amounts(approvals.get("totals"), 53_000, 0, 53_000, 4, 0, "hasData");
            for (var item : approvals.get("items")) assertThat(item.get("canViewPayment").asBoolean()).isTrue();
            var empty = ok(s, "/api/sales/ledger?" + branchIds(second) + "&" + november());
            assertThat(empty.get("items")).isEmpty();
            assertThat(empty.at("/meta/total").asLong()).isZero();
            assertThat(empty.at("/aggregation/dataStatus").asString()).isEqualTo("noData");
            amounts(empty.get("totals"), 0, 0, 0, 0, 0, "noData");
        }
        // sales:read without payments:read: the ledger is readable, the detail link is not offered.
        try (var s = login(salesOnly)) {
            var page = ok(s, base);
            for (var item : page.get("items")) assertThat(item.get("canViewPayment").asBoolean()).isFalse();
            // The payment detail keeps its own payments:read check (aggregation-api.md: 403).
            assertThat(get(s, "/api/payments/" + p3).statusCode()).isEqualTo(403);
        }
        try (var s = login(reader)) {
            assertThat(get(s, "/api/payments/" + p3).statusCode()).isEqualTo(200);
        }
    }

    @Test void theLedgerAndTheRevenueAgreeForEveryConditionOfTheExample() throws Exception {
        workedExample();
        var periods = List.of(october(), november(), december(), period("2026-10-01T00:00", "2027-01-01T00:00"));
        var purposes = List.of("", "&purpose=" + enc("이용권"), "&purpose=" + enc("스터디룸"), "&purpose=" + enc("사물함"));
        var branchSets = List.of(branchIds(first), branchIds(second), branchIds(first, second));
        int checked = 0;
        try (var s = login(reader)) {
            for (String when : periods) for (String basis : List.of("processedDate", "paymentDate"))
                for (String purpose : purposes) for (String where : branchSets) {
                    String query = where + "&" + when + "&refundBasis=" + basis + purpose;
                    var revenue = ok(s, "/api/sales/revenue?" + query);
                    var comparison = ok(s, "/api/sales/revenue/branches?" + query);
                    var ledger = ok(s, "/api/sales/ledger?" + query + "&pageSize=100");
                    var totals = revenue.get("totals");
                    assertThat(comparison.get("totals")).as(query).isEqualTo(totals);
                    assertThat(ledger.get("totals")).as(query).isEqualTo(totals);
                    long approved = 0, refunded = 0, approvals = 0, refunds = 0;
                    for (var item : ledger.get("items")) {
                        if ("승인".equals(item.get("entryType").asString())) { approved += item.get("amountKrw").asLong(); approvals++; }
                        else { refunded -= item.get("amountKrw").asLong(); refunds++; }
                    }
                    assertThat(approved).as(query).isEqualTo(totals.get("approvedAmountKrw").asLong());
                    assertThat(refunded).as(query).isEqualTo(totals.get("refundAmountKrw").asLong());
                    assertThat(approved - refunded).as(query).isEqualTo(totals.get("netAmountKrw").asLong());
                    assertThat(approvals).as(query).isEqualTo(totals.get("approvedPaymentCount").asLong());
                    assertThat(refunds).as(query).isEqualTo(totals.get("refundCount").asLong());
                    assertThat(ledger.at("/meta/total").asLong()).as(query).isEqualTo(revenue.at("/aggregation/sourceRowCount").asLong());
                    assertThat(ledger.at("/aggregation/sourceRowCount").asLong()).as(query).isEqualTo(ledger.at("/meta/total").asLong());
                    assertThat(comparison.at("/aggregation/sourceRowCount").asLong()).as(query)
                            .isEqualTo(revenue.at("/aggregation/sourceRowCount").asLong());
                    long branchSum = 0;
                    for (var row : comparison.get("items")) branchSum += row.get("netAmountKrw").asLong();
                    assertThat(branchSum).as(query).isEqualTo(totals.get("netAmountKrw").asLong());
                    long purposeSum = 0;
                    for (var row : revenue.get("byPurpose")) purposeSum += row.get("netAmountKrw").asLong();
                    assertThat(purposeSum).as(query).isEqualTo(totals.get("netAmountKrw").asLong());
                    checked++;
                }
            // The pass rows of the ledger reproduce the pass counts the contract derives from it (#88 will expose them).
            var passes = ok(s, "/api/sales/ledger?" + branchIds(first) + "&" + october() + "&refundBasis=paymentDate&purpose=" + enc("이용권"));
            int sold = 0, refundedQuantity = 0;
            for (var item : passes.get("items")) {
                if (item.get("passQuantity").asInt() > 0) sold += item.get("passQuantity").asInt();
                else refundedQuantity -= item.get("passQuantity").asInt();
            }
            assertThat(sold).isEqualTo(1);
            assertThat(refundedQuantity).isEqualTo(1);
        }
        assertThat(checked).isEqualTo(96);
    }

    static List<String> ids(JsonNode page) {
        var ids = new ArrayList<String>();
        for (var item : page.get("items")) ids.add(item.get("id").asString());
        return ids;
    }

    static long sum(JsonNode page) {
        long total = 0;
        for (var item : page.get("items")) total += item.get("amountKrw").asLong();
        return total;
    }
}
