package com.openstudy.modules.sales;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #95 (M10-D05): each registered sales analysis gives the same numbers as the M9 sales API for the same branches and
 * period, on the worked example of aggregation-api.md. The AI reference time is pinned to 2026-12-10 (Asia/Seoul):
 * "이번 달" is 12-01~12-11 and "지난달" is November. Before the fix the analyses selected payments by their row
 * creation time, so every fixture payment (all created today) landed in every period.
 */
@MySqlIntegrationTest
@TestPropertySource(properties = "openstudy.ai.fixed-as-of=2026-12-10T03:00:00Z")
class AiSalesEquivalenceIntegrationTests extends SalesIntegrationSupport {

    JsonNode ask(Session s, String question) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("question", question);
        body.put("scope", "all");
        var asked = http(s, "POST", "/api/ai/queries", body, null);
        assertThat(asked.statusCode()).as(asked.body()).isEqualTo(200);
        JsonNode answer = node(asked);
        assertThat(answer.get("status").asString()).as(asked.body()).isEqualTo("COMPLETED");
        return answer;
    }

    @Test void theRegisteredSalesAnalysesMatchTheM9Api() throws Exception {
        workedExample();
        long empty = branch("매출 빈 지점");
        String branches = branchIds(first, second, empty);
        try (var s = login(owner)) {
            // POC-001 this month (12-01~12-11) = GET /sales/revenue/branches.
            JsonNode rows = dataset(ask(s, "지점별 매출을 비교해줘").get("envelope"), "branchSales").get("rows");
            JsonNode api = ok(s, "/api/sales/revenue/branches?" + branches + "&" + period("2026-12-01T00:00", "2026-12-11T00:00"));
            for (var item : api.get("items")) {
                JsonNode row = rowOf(rows, "branch_id", item.get("branchId").asString());
                assertThat(row).as("branch %s in %s", item.get("branchId"), rows).isNotNull();
                assertThat(row.get("approved_krw").asLong()).isEqualTo(item.get("approvedAmountKrw").asLong());
                assertThat(row.get("refund_krw").asLong()).isEqualTo(item.get("refundAmountKrw").asLong());
                assertThat(row.get("net_krw").asLong()).isEqualTo(item.get("netAmountKrw").asLong());
                assertThat(row.get("approved_count").asLong()).isEqualTo(item.get("approvedPaymentCount").asLong());
                assertThat(row.get("refund_count").asLong()).isEqualTo(item.get("refundCount").asLong());
            }
            // The worked example's December: branch 1 has only P6's refund (-12,000), branch 2 has P7 (9,000).
            assertThat(rowOf(rows, "branch_id", Long.toString(first)).get("net_krw").asLong()).isEqualTo(-12_000);
            assertThat(rowOf(rows, "branch_id", Long.toString(second)).get("net_krw").asLong()).isEqualTo(9_000);
            assertThat(rowOf(rows, "branch_id", Long.toString(empty)).get("net_krw").asLong()).isZero();

            // POC-008 last month (November) = GET /sales/revenue byPurpose.
            JsonNode purposes = dataset(ask(s, "어디서 매출이 가장 많이 나왔어?").get("envelope"), "purposeSales").get("rows");
            JsonNode revenue = ok(s, "/api/sales/revenue?" + branches + "&" + november());
            Map<String, String> codes = Map.of("이용권", "PASS", "스터디룸", "ROOM", "사물함", "LOCKER");
            for (var purpose : revenue.get("byPurpose")) {
                JsonNode row = rowOf(purposes, "purpose", codes.get(purpose.get("purpose").asString()));
                assertThat(row).as("%s in %s", purpose, purposes).isNotNull();
                assertThat(row.get("net_krw").asLong()).isEqualTo(purpose.get("netAmountKrw").asLong());
                assertThat(row.get("approved_krw").asLong()).isEqualTo(purpose.get("approvedAmountKrw").asLong());
                assertThat(row.get("refund_krw").asLong()).isEqualTo(purpose.get("refundAmountKrw").asLong());
            }
            amounts(revenue.get("totals"), 53_000, 16_000, 37_000, 4, 2, "hasData");

            // POC-003 last month (November) = GET /sales/passes/products ranking.
            JsonNode top = dataset(ask(s, "인기 이용권 TOP 5 보여줘").get("envelope"), "topProducts").get("rows");
            JsonNode products = ok(s, "/api/sales/passes/products?" + branches + "&" + november() + "&page=1&pageSize=5");
            assertThat(top).hasSize(products.get("items").size());
            for (int i = 0; i < top.size(); i++) {
                JsonNode item = products.get("items").get(i);
                assertThat(top.get(i).get("product_id").asString()).isEqualTo(item.get("productId").asString());
                assertThat(top.get(i).get("net_units").asLong()).isEqualTo(item.get("netSoldQuantity").asLong());
                assertThat(top.get(i).get("issued_units").asLong()).isEqualTo(item.get("soldQuantity").asLong());
                assertThat(top.get(i).get("refunded_units").asLong()).isEqualTo(item.get("refundedQuantity").asLong());
            }
        }
    }

    static JsonNode dataset(JsonNode envelope, String id) {
        for (var dataset : envelope.get("datasets")) if (dataset.get("id").asString().equals(id)) return dataset;
        throw new AssertionError("no dataset " + id + " in " + envelope);
    }

    static JsonNode rowOf(JsonNode rows, String key, String value) {
        for (var row : rows) if (row.get(key).asString().equals(value)) return row;
        return null;
    }

    @org.junit.jupiter.api.AfterEach void cleanAi() {
        String actors = people.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        jdbc.update("DELETE FROM ai_executions WHERE employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM ai_conversations WHERE employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM ai_traces WHERE employee_id IN (" + actors + ")");
    }
}
