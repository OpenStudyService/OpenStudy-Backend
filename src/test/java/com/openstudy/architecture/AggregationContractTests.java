package com.openstudy.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #86: the published M9 contract defines every aggregation, ledger and home operation, requires sales:read at
 * every requested branch (M9-D07), carries one metadata shape on every aggregation (FR-AGG-09), keeps zero, no data and
 * not applicable apart (M9-D09), fixes the metric definitions where M10 reads them (M9-D03·D05·D06, M10-D05) and pins
 * the worked examples of both refund bases, a refund across a period boundary and a refund processed at another branch.
 */
class AggregationContractTests {
    static final Path DESIGN = Path.of("docs/design/foundation");
    static final Map<String, String> OPERATIONS = Map.ofEntries(
            Map.entry("getRevenueSummary", "get /sales/revenue"),
            Map.entry("compareBranchRevenue", "get /sales/revenue/branches"),
            Map.entry("listSalesLedger", "get /sales/ledger"),
            Map.entry("getPassSalesVolume", "get /sales/passes/volume"),
            Map.entry("listPassProductSales", "get /sales/passes/products"),
            Map.entry("getSalesTrend", "get /sales/trend"),
            Map.entry("getHomeSummary", "get /home/summary"));
    static final Map<String, String> ISSUES = Map.of("getRevenueSummary", "#87", "compareBranchRevenue", "#87",
            "listSalesLedger", "#87", "getPassSalesVolume", "#88", "listPassProductSales", "#88", "getSalesTrend", "#88",
            "getHomeSummary", "#90");
    static final Map<String, String> RESPONSES = Map.of("getRevenueSummary", "RevenueSummary",
            "compareBranchRevenue", "BranchRevenueComparison", "listSalesLedger", "SalesLedgerPage",
            "getPassSalesVolume", "PassVolumeSummary", "listPassProductSales", "PassProductSalesPage",
            "getSalesTrend", "SalesTrend", "getHomeSummary", "HomeSummary");
    static final List<String> SALES = List.of("getRevenueSummary", "compareBranchRevenue", "listSalesLedger",
            "getPassSalesVolume", "listPassProductSales", "getSalesTrend");
    static final List<String> METRICS = List.of("sales.approved_amount", "sales.refund_amount", "sales.net_amount",
            "sales.approved_payment_count", "sales.refund_count", "pass.payment_count", "pass.sold_quantity",
            "pass.refunded_quantity", "pass.net_sold_quantity", "pass.issued_count", "home.task.today_count",
            "home.task.completed_today_count", "home.task.completion_rate");

    final JsonNode spec = read();

    static JsonNode read() {
        try {
            return JsonMapper.builder().build().readTree(Files.readString(DESIGN.resolve("openapi.json")));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String contract() throws java.io.IOException {
        return Files.readString(DESIGN.resolve("aggregation-api.md"));
    }

    JsonNode operation(String id) {
        String[] methodAndPath = OPERATIONS.get(id).split(" ");
        return spec.get("paths").get(methodAndPath[1]).get(methodAndPath[0]);
    }

    JsonNode resolve(JsonNode node) {
        if (!node.has("$ref")) return node;
        return spec.at(node.get("$ref").asString().substring(1));
    }

    JsonNode schema(String name) {
        return spec.at("/components/schemas/" + name);
    }

    List<String> names(JsonNode node) {
        var names = new ArrayList<String>();
        node.get("properties").properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    List<String> parameters(String id) {
        var names = new ArrayList<String>();
        for (var parameter : operation(id).get("parameters")) names.add(resolve(parameter).get("name").asString());
        return names;
    }

    String response(String id) {
        return operation(id).at("/responses/200/content/application~1json/schema/$ref").asString();
    }

    @Test void everyAggregationOperationDefinesItsErrorResponsesAndIsDocumented() throws Exception {
        String contract = contract();
        String common = Files.readString(DESIGN.resolve("api.md"));
        OPERATIONS.forEach((id, route) -> {
            var operation = operation(id);
            assertThat(operation.get("operationId").asString()).isEqualTo(id);
            for (String status : List.of("401", "403", "404", "409", "422", "429", "500")) {
                assertThat(operation.get("responses").has(status)).as("%s %s", id, status).isTrue();
            }
            assertThat(response(id)).as(id).endsWith("/" + RESPONSES.get(id));
            assertThat(operation.get("x-permission").asString()).as(id).isNotBlank();
            assertThat(operation.get("x-error-codes").toString()).as(id).contains("\"FORBIDDEN\"", "\"VALIDATION_FAILED\"");
            assertThat(operation.get("x-adr-decisions").toString()).as(id).contains("M9-D");
            assertThat(operation.has("requestBody")).as(id).isFalse();
            String[] methodAndPath = route.split(" ");
            assertThat(contract).as(route).contains("`" + methodAndPath[0].toUpperCase() + " " + methodAndPath[1]);
            for (var code : operation.get("x-error-codes")) {
                assertThat(contract + common).as("%s %s", id, code).contains(code.asString());
            }
        });
    }

    @Test void theContractIsSplitAcrossTheImplementationIssues() throws Exception {
        assertThat(spec.at("/info/version").asString()).isEqualTo("0.1.11-m9-aggregation");
        assertThat(spec.at("/info/description").asString()).contains("Issue #86의 설계 계약(aggregation-api.md, ADR-M9-001)");
        OPERATIONS.keySet().forEach(id ->
                assertThat(operation(id).get("x-implementation-issue").asString()).as(id).isEqualTo(ISSUES.get(id)));
        assertThat(contract()).contains("`0.1.11-m9-aggregation`", "스키마는 바꾸지 않는다");
    }

    @Test void issues87To90ImplementedEveryAggregationAndTheHome() throws Exception {
        OPERATIONS.keySet().forEach(id -> assertThat(operation(id).get("x-implementation-status").asString()).as(id)
                .isEqualTo("implemented"));
        assertThat(spec.at("/info/description").asString()).contains("매출·지점 비교·최소 원장 operation은 #87에서 구현됐다",
                "이용권 판매량·상품 순위·추세 operation은 #88에서 구현됐다", "운영 홈 요약 operation은 #90에서 구현됐다");
        assertThat(contract()).contains("매출·지점 비교·최소 원장 operation 3개(`GET /sales/revenue`, `GET /sales/revenue/branches`, "
                + "`GET /sales/ledger`)는 #87에서 구현됐다(`implemented`)",
                "이용권 판매량·상품 순위·추세 operation 3개(`GET /sales/passes/volume`, `GET /sales/passes/products`, "
                + "`GET /sales/trend`)는 #88에서 구현됐다(`implemented`)",
                "`modules/sales`에는 #87의 공통 집계 기반과 매출·지점 비교·최소 원장, #88의 이용권 판매량·상품 순위·추세가 있다");
    }

    @Test void everyRequestedBranchNeedsSalesReadAndNoneIsDroppedSilently() throws Exception {
        for (String id : SALES) {
            assertThat(operation(id).get("x-permission").asString()).as(id).contains("요청한 모든 지점의 sales:read", "M9-D07");
            assertThat(operation(id).get("x-adr-decisions").toString()).as(id).contains("M9-D07");
            assertThat(parameters(id)).as(id).contains("branchIds", "from", "to", "refundBasis").doesNotContain("scope");
            assertThat(operation(id).get("description").asString()).as(id).contains("조용히 제외하지 않는다");
        }
        var branchIds = spec.at("/components/parameters/AggregationBranchIds");
        assertThat(branchIds.at("/schema/uniqueItems").asBoolean()).isTrue();
        assertThat(branchIds.get("explode").asBoolean()).isTrue();
        assertThat(branchIds.get("description").asString()).contains("403", "조용히 제외하지 않는다");
        assertThat(contract()).contains("`AuthorizationService.require(state, branchIds, BranchAction.READ_SALES)`",
                "지정한 지점 중 **하나라도** `sales:read`가 없으면 403이다", "없는 지점 ID도 403이다",
                "지점 조건은 **원결제 지점**(`payments.branch_id`)에 건다");
    }

    @Test void theLedgerIsMinimalAndPaymentDetailIsSeparate() throws Exception {
        var entry = schema("SalesLedgerEntry");
        assertThat(entry.get("additionalProperties").asBoolean()).isFalse();
        assertThat(names(entry)).contains("paymentId", "refundId", "branchId", "processingBranchId", "purpose", "productName",
                "approvedAt", "refundedAt", "amountKrw", "basisAt", "canViewPayment")
                .noneMatch(field -> field.toLowerCase().contains("customer") || field.toLowerCase().contains("phone")
                        || field.equals("method") || field.equals("reason") || field.toLowerCase().contains("actor")
                        || field.equals("processedBy") || field.equals("createdBy"));
        assertThat(operation("listSalesLedger").get("x-permission").asString()).contains("payments:read 별도");
        assertThat(parameters("listSalesLedger")).contains("page", "pageSize", "direction", "entryType");
        assertThat(names(schema("SalesLedgerPage"))).containsExactlyInAnyOrder("items", "meta", "aggregation", "totals");
        assertThat(contract()).contains("고객 이름·연락처·회원 번호·고객 ID, 결제 수단, 처리자, 환불 사유",
                "`Σ(전체 항목 amountKrw)` = `netAmountKrw`", "원장 `meta.total` = `aggregation.sourceRowCount`");
    }

    @Test void everyAggregationCarriesTheSameMetadata() {
        for (String id : SALES) {
            var body = spec.at(response(id).substring(1));
            assertThat(body.at("/properties/aggregation/$ref").asString()).as(id).endsWith("/AggregationMeta");
        }
        var meta = schema("AggregationMeta");
        assertThat(names(meta)).containsExactlyInAnyOrder("condition", "defaultPeriod", "timeZone", "currency", "asOf",
                "rowCount", "sourceRowCount", "dataStatus", "partial", "metrics");
        assertThat(meta.get("required").size()).isEqualTo(10);
        assertThat(meta.at("/properties/timeZone/enum").toString()).isEqualTo("[\"Asia/Seoul\"]");
        assertThat(meta.at("/properties/currency/enum").toString()).isEqualTo("[\"KRW\"]");
        assertThat(names(schema("AggregationCondition"))).containsExactlyInAnyOrder("branchIds", "allBranches", "from", "to",
                "refundBasis", "purpose", "productId", "categoryCode", "granularity");
        assertThat(names(schema("AggregationPartial"))).containsExactlyInAnyOrder("periodOpen", "firstBucketPartial", "lastBucketPartial");
        assertThat(spec.at("/components/parameters/RefundBasis/schema/default").asString()).isEqualTo("processedDate");
    }

    @Test void zeroNoDataAndNotApplicableAreDifferentValues() throws Exception {
        assertThat(schema("DataStatus").get("enum").toString()).isEqualTo("[\"hasData\",\"noData\"]");
        var ratio = schema("Ratio");
        assertThat(names(ratio)).containsExactlyInAnyOrder("state", "percent", "numerator", "denominator");
        assertThat(ratio.at("/properties/state/enum").toString()).isEqualTo("[\"value\",\"notApplicable\"]");
        assertThat(ratio.at("/properties/percent/nullable").asBoolean()).isTrue();
        assertThat(ratio.at("/properties/percent/description").asString()).contains("HALF_UP");
        for (String name : List.of("RevenueAmounts", "PurposeRevenue", "BranchRevenue", "PassVolume", "PassProductSales")) {
            assertThat(schema(name).at("/properties/dataStatus/$ref").asString()).as(name).endsWith("/DataStatus");
        }
        assertThat(schema("HomeTaskCounts").at("/properties/completionRate/allOf/0/$ref").asString()).endsWith("/Ratio");
        assertThat(schema("RevenueAmounts").at("/properties/approvedAmountKrw/minimum").asLong()).isZero();
        assertThat(schema("RevenueAmounts").at("/properties/netAmountKrw/minimum").asLong()).isNegative();
        assertThat(schema("PassVolume").at("/properties/netSoldQuantity/minimum").asLong()).isNegative();
        assertThat(contract()).contains(
                "| 0 | 0 | `notApplicable` | null |", "| 0 | 4 | `value` | 0.0 |", "| 1 | 3 | `value` | 33.3 |",
                "| 2 | 3 | `value` | 66.7 |", "| 1 | 16 | `value` | 6.3 |", "1 ÷ 16 = 6.25는 HALF_UP으로 6.3이다");
    }

    @Test void thePeriodDefaultsToThisMonthAndRefusesMoreThan366Days() throws Exception {
        for (String id : SALES) {
            assertThat(operation(id).get("x-error-codes").toString()).as(id).contains("\"PERIOD_TOO_LONG\"");
            assertThat(operation(id).get("description").asString()).as(id).contains("366일 초과는 422 PERIOD_TOO_LONG");
        }
        assertThat(spec.at("/components/parameters/AggregationFrom/description").asString()).contains("이번 달 1일 0시(Asia/Seoul)");
        assertThat(spec.at("/components/parameters/AggregationTo/description").asString()).contains("내일 0시(Asia/Seoul)", "366일 이하");
        assertThat(contract()).contains(
                "| 2026년 전체 + 하루 | `2026-01-01T00:00+09:00` | `2027-01-02T00:00+09:00` | 366일, 200 |",
                "| 366일 + 1마이크로초 | `2026-01-01T00:00+09:00` | `2027-01-02T00:00:00.000001+09:00` | 422 `PERIOD_TOO_LONG` |",
                "| 같은 시각 | `2026-11-05T00:00+09:00` | `2026-11-05T00:00+09:00` | 422 `VALIDATION_FAILED` |",
                "`2026-11-01T00:00+09:00`(=`2026-10-31T15:00:00Z`) ~ `2026-11-06T00:00+09:00`(=`2026-11-05T15:00:00Z`), `defaultPeriod=true`");
    }

    /** 2026-11-05 the user confirmed 422 PERIOD_TOO_LONG for a period over 366 days and ADR-M9-001 now says so. */
    @Test void aPeriodOver366DaysIsConfirmedAs422() throws Exception {
        assertThat(spec.at("/components/responses").has("Error400")).isFalse();
        for (String id : OPERATIONS.keySet()) assertThat(operation(id).get("responses").has("400")).as(id).isFalse();
        for (String id : SALES) {
            assertThat(operation(id).at("/responses/422/$ref").asString()).as(id).endsWith("/Error422");
        }
        assertThat(contract()).contains("### 입력 오류의 상태 코드: 422(사용자 확정)", "**사용자 확정: 422.**",
                "#87 이슈 본문의 인수 조건은 아직 \"400\"이라 422로 맞춰야 한다");
        String adr = Files.readString(Path.of("docs/db/ADR-M9-001.md"));
        assertThat(adr).contains("366일을 넘는 기간은 422 `PERIOD_TOO_LONG`으로 거부한다", "| 2026-10-28 | 최초 확정 |",
                "| 2026-11-05 | 366일 초과 응답을 400에서 422 `PERIOD_TOO_LONG`으로 변경(기존 입력 범위 오류와 통일, 사용자 결정).")
                .doesNotContain("400으로 거부한다");
    }

    /** 2026-11-05 user decision: the home shift card alone shows same-branch colleagues, by name and planned times only. */
    @Test void theHomeShiftCardShowsColleaguesByNameAndTimesOnly() throws Exception {
        var colleague = schema("HomeShiftColleague");
        assertThat(colleague.get("additionalProperties").asBoolean()).isFalse();
        assertThat(names(colleague)).containsExactlyInAnyOrder("branchId", "employeeName", "startsAt", "endsAt");
        var card = schema("HomeShiftCard");
        assertThat(names(card)).containsExactlyInAnyOrder("count", "items", "colleagueCount", "colleagues", "link", "source");
        assertThat(card.at("/properties/colleagues/items/$ref").asString()).endsWith("/HomeShiftColleague");
        assertThat(card.at("/properties/colleagues/maxItems").asInt()).isEqualTo(5);
        assertThat(operation("getHomeSummary").get("x-permission").asString()).contains("colleagues", "2026-11-05 사용자 결정");
        assertThat(contract()).contains("#### 같은 지점 동료의 오늘 근무(2026-11-05 사용자 결정)", "**홈 카드만의 예외**",
                "일반 직원의 `colleagues`는 `GET /shifts`로 재현되지 않는다",
                "`shifts` 이벤트의 전달 대상(기록을 볼 수 있는 OWNER·기록 지점 MANAGER·직원 본인)은 바꾸지 않는다");
        assertThat(Files.readString(Path.of("docs/db/ADR-M8-001.md"))).contains(
                "| 2026-11-05 | 홈 요약의 오늘 근무 카드에서 같은 지점 동료의 이름·근무 시각만 공개(FR-DASH-05, 사용자 결정). 근무표·근태 API의 조회 범위는 그대로.");
        assertThat(Files.readString(Path.of("docs/db/ADR-M9-001.md"))).contains("같은 지점 동료 근무를 직원 이름과 근무 시작·종료 시각만으로");
    }

    /** The colleague exception must not widen the M8 operations: their scope wording and shapes stay as #82 published them. */
    @Test void theWorkforceOperationsKeepTheirM8D02Scope() {
        for (String path : List.of("/shifts", "/shifts/weekly", "/attendance-records")) {
            assertThat(spec.at("/paths/" + path.replace("/", "~1") + "/get/x-permission").asString()).as(path)
                    .contains("M8-D02", "OWNER 전체, 기록 지점의 MANAGER는 그 지점 전체, 그 외 본인 기록").doesNotContain("동료", "colleague");
        }
        for (String path : List.of("/shifts/{id}", "/attendance-records/{id}")) {
            assertThat(spec.at("/paths/" + path.replace("/", "~1") + "/get/x-permission").asString()).as(path)
                    .contains("M8-D02").doesNotContain("동료", "colleague");
        }
        assertThat(spec.at("/paths/~1shifts/get/parameters").toString()).doesNotContain("colleague");
        assertThat(names(schema("Shift"))).doesNotContain("colleagues");
        assertThat(names(schema("HomeShiftItem"))).containsExactlyInAnyOrder("id", "branchId", "employee", "startsAt", "endsAt", "link");
    }

    @Test void metricDefinitionsLiveWhereM10ReadsThemAndMatchTheContract() throws Exception {
        var definitions = spec.get("x-metric-definitions");
        var ids = new ArrayList<String>();
        for (var definition : definitions) {
            ids.add(definition.get("id").asString());
            for (String field : List.of("name", "unit", "definition", "includes", "excludes", "branchAttribution", "timeBasis",
                    "refundBasisApplies", "dimensions", "source", "operations", "adr")) {
                assertThat(definition.has(field)).as("%s %s", definition.get("id"), field).isTrue();
            }
            for (var operationId : definition.get("operations")) {
                assertThat(OPERATIONS).as(definition.get("id").asString()).containsKey(operationId.asString());
            }
            assertThat(schema("MetricUnit").get("enum").toString()).contains("\"" + definition.get("unit").asString() + "\"");
            assertThat(contract()).as(definition.get("id").asString()).contains("| `" + definition.get("id").asString() + "` | "
                    + definition.get("name").asString() + " | " + definition.get("unit").asString() + " |");
        }
        assertThat(ids).containsExactlyElementsOf(METRICS);
        for (String name : List.of("RevenueAmounts", "PassVolume")) {
            schema(name).get("properties").properties().forEach(entry -> {
                if (!entry.getKey().equals("dataStatus"))
                    assertThat(METRICS).as("%s.%s", name, entry.getKey()).contains(entry.getValue().get("x-metric-id").asString());
            });
        }
        assertThat(contract()).contains("OpenAPI 최상위 `x-metric-definitions`", "M10-D05", "`AggregationCondition`");
    }

    @Test void metricsFollowM9D03D05D06() {
        var byId = new java.util.HashMap<String, JsonNode>();
        spec.get("x-metric-definitions").forEach(definition -> byId.put(definition.get("id").asString(), definition));
        assertThat(byId.get("sales.approved_amount").get("includes").asString()).contains("이용권·스터디룸·사물함");
        for (String id : List.of("sales.approved_amount", "sales.net_amount")) {
            assertThat(byId.get(id).get("excludes").asString()).as(id).contains("실패 결제", "이용권 소비 차감");
        }
        for (String id : List.of("sales.refund_amount", "sales.refund_count", "pass.refunded_quantity")) {
            assertThat(byId.get(id).get("branchAttribution").asString()).as(id).contains("원결제의 payments.branch_id");
            assertThat(byId.get(id).get("timeBasis").asString()).as(id).contains("refunds.refunded_at", "payments.approved_at");
            assertThat(byId.get(id).get("refundBasisApplies").asBoolean()).as(id).isTrue();
        }
        assertThat(byId.get("sales.refund_amount").get("branchAttribution").asString()).contains("processing_branch_id가 아니다");
        for (String id : List.of("pass.payment_count", "pass.sold_quantity", "pass.issued_count", "sales.approved_amount")) {
            assertThat(byId.get(id).get("refundBasisApplies").asBoolean()).as(id).isFalse();
        }
        assertThat(byId.get("pass.issued_count").get("definition").asString()).contains("이후 환불된 이용권도 포함");
        assertThat(byId.get("pass.payment_count").get("unit").asString()).isEqualTo("건");
        assertThat(byId.get("pass.net_sold_quantity").get("unit").asString()).isEqualTo("개");
        assertThat(byId.get("pass.issued_count").get("unit").asString()).isEqualTo("개");
        for (String id : List.of("pass.payment_count", "pass.sold_quantity", "pass.net_sold_quantity", "pass.issued_count")) {
            assertThat(byId.get(id).get("dimensions").toString()).as(id).contains("\"product\"").doesNotContain("\"purpose\"");
        }
        assertThat(byId.get("sales.net_amount").get("dimensions").toString()).contains("\"purpose\"");
    }

    @Test void revenueCoversEveryPurposeAndPassMetricsOnlyPasses() {
        for (String id : List.of("getRevenueSummary", "compareBranchRevenue", "listSalesLedger", "getSalesTrend")) {
            assertThat(parameters(id)).as(id).contains("purpose");
        }
        for (String id : List.of("getPassSalesVolume", "listPassProductSales")) {
            assertThat(parameters(id)).as(id).contains("productId", "categoryCode").doesNotContain("purpose");
        }
        assertThat(spec.at("/components/parameters/SalesPurpose/schema/enum").toString())
                .isEqualTo("[\"이용권\",\"스터디룸\",\"사물함\"]");
        assertThat(schema("RevenueSummary").at("/properties/byPurpose/description").asString()).contains("항상 세 행");
        assertThat(names(schema("PassVolume"))).containsExactlyInAnyOrder("paymentCount", "soldQuantity", "refundedQuantity",
                "netSoldQuantity", "issuedCount", "dataStatus");
    }

    @Test void productSalesUseTheSaleSnapshotAndAStableOrder() throws Exception {
        assertThat(names(schema("PassProductSales"))).contains("rank", "productId", "productName", "categoryCode", "categoryName");
        assertThat(schema("PassProductSales").at("/properties/productName/description").asString()).contains("snapshot_product_name");
        assertThat(parameters("listPassProductSales")).doesNotContain("sort", "direction");
        assertThat(operation("listPassProductSales").get("description").asString())
                .contains("netSoldQuantity 내림차순", "productId·productName·categoryCode·categoryName 오름차순");
        assertThat(contract()).contains("`(passes.product_id, passes.snapshot_product_name, passes.snapshot_category_code, passes.snapshot_category_name)`",
                "(10, 7, 7, 3 → 1, 2, 2, 4)");
    }

    @Test void trendsStartOnMondayFillEveryBucketAndMarkPartialOnes() throws Exception {
        var trend = operation("getSalesTrend");
        assertThat(parameters("getSalesTrend")).contains("granularity", "measure");
        assertThat(trend.get("x-adr-decisions").toString()).contains("M9-D04");
        assertThat(trend.get("description").asString()).contains("월요일 0시(Asia/Seoul)", "0과 noData로 채운다", "partial");
        assertThat(names(schema("SalesTrendBucket"))).containsExactlyInAnyOrder("label", "bucketStart", "bucketEnd", "start", "end",
                "partial", "revenue", "passes");
        assertThat(contract()).contains(
                "| 2026-10-26 | 11-01 ~ 11-02 | true | 6,000 | 0 | 6,000 | `hasData` |",
                "| 2026-11-16 | 11-16 ~ 11-23 | false | 0 | 0 | 0 | `noData` |",
                "| 2026-11-30 | 11-30 ~ 12-01 | true | 15,000 | 0 | 15,000 | `hasData` |",
                "일 최대 367개", "주 최대 54개", "월 최대 13개");
    }

    @Test void workedExamplesPinBothRefundBasesAndTheOriginalBranch() throws Exception {
        assertThat(contract()).contains(
                "| 11월, 처리일 | 1 | 53,000 | 16,000 | 37,000 | 4 | 2 | `hasData` |",
                "| 11월, 원결제일 | 1 | 53,000 | 18,000 | 35,000 | 4 | 2 | `hasData` |",
                "| 11월, 두 기준 | 2 | 0 | 0 | 0 | 0 | 0 | `noData` |",
                "| 12월, 처리일 | 1 | 0 | 12,000 | −12,000 | 0 | 1 | `hasData` | 다른 지점에서 처리한 P6 환불도 원결제 지점 1에서 차감 |",
                "| 10월, 원결제일 | 1 | 10,000 | 10,000 | 0 | 1 | 1 | `hasData` |",
                "| 10월, 원결제일 | 1 | 1 | 1 | 0 | 1 | 환불된 판매는 순판매량에서 빠지고 발급 건수에 남음 |",
                "| 12월, 처리일 | 0 | 0 | 1 | −1 | 0 | 이전 기간 판매의 환불 |");
    }

    @Test void homeFollowsTheSourceFeaturesAndNeverWidensTheirScope() throws Exception {
        var home = operation("getHomeSummary");
        assertThat(parameters("getHomeSummary")).containsExactly("scope", "mine", "assigneeId");
        assertThat(home.get("x-permission").asString()).contains("M8-D02").doesNotContain("sales:read");
        assertThat(home.get("x-adr-decisions").toString()).contains("M9-D08", "M8-D02");
        // ADR-M10-002 M10-D11: team chat left the service, so the home has no message card.
        assertThat(names(schema("HomeSummary"))).containsExactlyInAnyOrder("meta", "seats", "tasks", "inquiries", "facilities",
                "shifts", "notices");
        assertThat(spec.at("/components/schemas/HomeMessageCard").isMissingNode()).isTrue();
        assertThat(spec.at("/components/schemas/HomeChannelItem").isMissingNode()).isTrue();
        assertThat(schema("HomeLink").at("/properties/screen/enum").toString()).doesNotContain("channel");
        assertThat(names(schema("HomeLink"))).containsExactlyInAnyOrder("screen", "branchId", "targetId", "query");
        for (String card : List.of("HomeSeatCard", "HomeInquiryCard", "HomeFacilityCard", "HomeShiftCard", "HomeNoticeCard")) {
            assertThat(names(schema(card))).as(card).contains("link", "source");
        }
        assertThat(names(schema("HomeTaskCard"))).contains("today", "mine", "items", "link", "sources");
        assertThat(names(schema("HomeInquiryItem"))).noneMatch(field -> field.toLowerCase().contains("customer"));
        assertThat(contract()).contains("| 근무 | M8 `GET /shifts`: **M8-D02 조회 범위**.",
                "근무 카드의 `count`·`items`는 M8-D02 조회 범위 그대로라 일반 직원에게는 **본인 근무만** 들어간다",
                "| `mine`·`assigneeId` 없음 | 선택 범위 전체 업무(`mine=false`) | 본인 담당 업무(`mine=true`) |");
    }

    @Test void todaysTasksAndShiftsFollowM9D08() throws Exception {
        assertThat(contract()).contains(
                "**오늘의 업무** = (완료가 아니고 `dueAt < dayEnd`인 업무) + (완료이고 `completedAt ∈ [dayStart, dayEnd)`인 업무)",
                "2026-11-05 10:00 기준 `total=3`, `open=2`, `overdue=1`, `completedToday=1`, 완료율 `33.3`이다",
                "| 11-04 22:00 ~ 11-05 06:00 | 예정 | 포함(`workDate`는 11-04) |",
                "`startsAt < dayEnd ∧ endsAt > dayStart`",
                "`dayStart=2026-11-04T15:00:00Z`, `dayEnd=2026-11-05T15:00:00Z`");
    }

    @Test void largeRangesStayBoundedAndNaturalLanguageStaysOut() throws Exception {
        for (String id : List.of("listSalesLedger", "listPassProductSales")) {
            assertThat(parameters(id)).as(id).contains("page", "pageSize");
        }
        for (String card : List.of("HomeTaskCard", "HomeInquiryCard", "HomeFacilityCard", "HomeShiftCard", "HomeNoticeCard")) {
            var items = schema(card).at("/properties/items");
            assertThat(items.get("maxItems").asInt()).as(card).isEqualTo(5);
        }
        spec.get("paths").properties().forEach(entry -> assertThat(entry.getKey()).doesNotContain("/ai", "natural", "query-spec"));
        assertThat(contract()).contains("## 범위 밖(M10·M13)", "자연어 질의, 동적 시각화");
    }

    @Test void judgementsAndTheLocalSampleAreRecorded() throws Exception {
        assertThat(contract()).contains("## 정책이 정하지 않아 이 계약에서 정한 것", "`GET /api/home` 샘플",
                "경로가 겹치지 않는다");
        assertThat(Files.readString(DESIGN.resolve("README.md"))).contains("aggregation-api.md", "0.1.11-m9-aggregation");
    }
}
