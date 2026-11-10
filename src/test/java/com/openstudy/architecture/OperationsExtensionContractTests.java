package com.openstudy.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #111: the published M13 contract defines every branch-closure, merge, daily-closing, usage and occupancy-trend
 * operation with its errors and the ADR-M13-001 permissions, gives each reason a product cannot be sold its own code, and
 * gives payment lists and sales aggregations the same advanced filter structure with the applied filter in the response.
 */
class OperationsExtensionContractTests {
    static final Path DESIGN = Path.of("docs/design/foundation");
    /** operationId → "method path", in the order of the contract document. */
    static final Map<String, String> OPERATIONS = new LinkedHashMap<>();
    static {
        OPERATIONS.put("listBranchClosures", "get /branches/{id}/closures");
        OPERATIONS.put("createBranchClosure", "post /branches/{id}/closures");
        OPERATIONS.put("updateBranchClosure", "patch /branch-closures/{id}");
        OPERATIONS.put("deleteBranchClosure", "delete /branch-closures/{id}");
        OPERATIONS.put("listCustomerDuplicates", "get /customer-duplicates");
        OPERATIONS.put("mergeCustomer", "post /customers/{id}/merge");
        OPERATIONS.put("listCustomerMerges", "get /customers/{id}/merges");
        OPERATIONS.put("listDailyClosings", "get /daily-closings");
        OPERATIONS.put("createDailyClosing", "post /daily-closings");
        OPERATIONS.put("getDailyClosing", "get /daily-closings/{id}");
        OPERATIONS.put("getPassUsageByBranch", "get /sales/passes/usage");
        OPERATIONS.put("getSeatOccupancyTrend", "get /seats/occupancy-trend");
    }
    static final Map<String, String> ISSUES = Map.ofEntries(
            Map.entry("listBranchClosures", "#112"), Map.entry("createBranchClosure", "#112"),
            Map.entry("updateBranchClosure", "#112"), Map.entry("deleteBranchClosure", "#112"),
            Map.entry("listCustomerDuplicates", "#114"), Map.entry("mergeCustomer", "#114"),
            Map.entry("listCustomerMerges", "#114"), Map.entry("listDailyClosings", "#115"),
            Map.entry("createDailyClosing", "#115"), Map.entry("getDailyClosing", "#115"),
            Map.entry("getPassUsageByBranch", "#116"), Map.entry("getSeatOccupancyTrend", "#116"));
    static final List<String> FILTERS = List.of("#/components/parameters/PaymentMethods",
            "#/components/parameters/ProductKinds", "#/components/parameters/OperatorIds",
            "#/components/parameters/ProcessingBranchIds");

    final JsonNode spec = read();

    static JsonNode read() {
        try {
            return JsonMapper.builder().build().readTree(Files.readString(DESIGN.resolve("openapi.json")));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String contract() throws java.io.IOException {
        return Files.readString(DESIGN.resolve("operations-extension-api.md"));
    }

    JsonNode operation(String id) {
        String[] methodAndPath = OPERATIONS.get(id).split(" ");
        return spec.get("paths").get(methodAndPath[1]).get(methodAndPath[0]);
    }

    JsonNode existing(String method, String path) {
        return spec.get("paths").get(path).get(method);
    }

    JsonNode schema(String name) {
        return spec.at("/components/schemas/" + name);
    }

    List<String> names(JsonNode properties) {
        var names = new ArrayList<String>();
        properties.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    List<String> texts(JsonNode array) {
        var values = new ArrayList<String>();
        for (var value : array) values.add(value.asString());
        return values;
    }

    /** The planned change of an implemented operation or schema for one implementation issue. */
    JsonNode planned(JsonNode target, String issue) {
        for (var change : target.get("x-planned-changes")) {
            if (change.get("issue").asString().equals(issue)) {
                assertThat(change.get("status").asString()).isEqualTo("design-target");
                return change;
            }
        }
        throw new AssertionError("no planned change for " + issue);
    }

    List<String> plannedParameters(String path, String issue) {
        var refs = new ArrayList<String>();
        for (var parameter : planned(existing("get", path), issue).get("parameters")) {
            refs.add(parameter.has("$ref") ? parameter.get("$ref").asString() : parameter.get("name").asString());
        }
        return refs;
    }

    @Test void everyOperationIsDefinedWithItsErrorsAndDocumented() throws Exception {
        String contract = contract();
        String common = Files.readString(DESIGN.resolve("api.md")) + Files.readString(DESIGN.resolve("aggregation-api.md"))
                + Files.readString(DESIGN.resolve("customer-sales-api.md"));
        OPERATIONS.forEach((id, route) -> {
            var operation = operation(id);
            assertThat(operation).as(route).isNotNull();
            assertThat(operation.get("operationId").asString()).isEqualTo(id);
            for (String status : List.of("401", "403", "404", "409", "422", "429", "500")) {
                assertThat(operation.get("responses").has(status)).as("%s %s", id, status).isTrue();
            }
            assertThat(operation.get("x-permission").asString()).as(id).isNotBlank();
            assertThat(operation.get("x-error-codes").size()).as(id).isPositive();
            assertThat(operation.get("x-adr-decisions").toString()).as(id).contains("M13-D");
            // #112 and #114 are implemented; the other issues are still design targets.
            assertThat(operation.get("x-implementation-status").asString()).as(id)
                    .isEqualTo(List.of("#112", "#114").contains(ISSUES.get(id)) ? "implemented" : "design-target");
            assertThat(operation.get("x-implementation-issue").asString()).as(id).isEqualTo(ISSUES.get(id));
            String[] methodAndPath = route.split(" ");
            assertThat(contract).as(route).contains("`" + methodAndPath[0].toUpperCase() + " " + methodAndPath[1]);
            for (var code : operation.get("x-error-codes")) {
                assertThat(contract + common).as("%s %s", id, code).contains(code.asString());
            }
        });
        assertThat(existing("delete", "/branch-closures/{id}").get("responses").has("204")).isTrue();
        for (String create : List.of("createBranchClosure", "createDailyClosing")) {
            assertThat(operation(create).get("parameters").toString()).as(create).contains("#/components/parameters/Idempotency");
            assertThat(operation(create).get("responses").has("201")).as(create).isTrue();
            assertThat(texts(operation(create).get("x-error-codes"))).contains("IDEMPOTENCY_CONFLICT", "REQUEST_IN_PROGRESS");
        }
        // Each 409 target field of the contract table is in the conflict body.
        assertThat(names(schema("OperationsConflictError").get("properties"))).contains("closureId", "closedDate",
                "salesStartsAt", "salesEndsAt", "salesLimit", "soldQuantity", "mergedIntoCustomerId", "closingId",
                "latestVersion", "reloadUrl");
        assertThat(contract).contains("| 409 | `BRANCH_CLOSURE_EXISTS` |", "| 409 | `BRANCH_CLOSED_DAY` |",
                "| 409 | `CUSTOMER_ALREADY_MERGED` |", "| 422 | `CLOSING_DATE_OPEN` |", "| 409 | `CLOSING_EXISTS` |");
    }

    /** Acceptance: holidays, sale period/limit, merges, closings, adjustments, advanced filters and occupancy trend. */
    @Test void changesToImplementedOperationsArePlannedForTheirIssues() {
        // #112 moved its planned changes into the definitions and left none behind.
        var booking = existing("post", "/room-reservations");
        assertThat(texts(booking.get("x-error-codes"))).contains("BRANCH_CLOSED_DAY");
        assertThat(booking.at("/responses/409/content/application~1json/schema/$ref").asString())
                .isEqualTo("#/components/schemas/OperationsConflictError");
        assertThat(texts(schema("RoomReservationCalendar").get("required"))).contains("closedDates");
        assertThat(texts(schema("HomeSummary").get("required"))).contains("closures");
        assertThat(schema("HomeSummary").at("/properties/closures/items/$ref").asString())
                .isEqualTo("#/components/schemas/HomeClosureItem");
        assertThat(spec.toString()).doesNotContain("\"issue\":\"#112\"");
        // #113 moved its planned changes into the product and payment definitions.
        assertThat(texts(schema("Product").get("required"))).contains("salesStartsAt", "salesEndsAt", "salesLimit",
                "soldQuantity", "remainingQuantity", "saleAvailability");
        assertThat(names(schema("ProductCreate").get("properties"))).contains("salesStartsAt", "salesEndsAt", "salesLimit");
        assertThat(names(schema("ProductUpdate").get("properties"))).contains("salesStartsAt", "salesEndsAt", "salesLimit");
        // Snapshots stored before #113 (schemaVersion 1) have no sale fields, so they are not required.
        assertThat(texts(schema("ProductSnapshot").get("required"))).doesNotContain("salesLimit");
        assertThat(schema("ProductSnapshot").at("/properties/schemaVersion/maximum").asInt()).isEqualTo(2);
        assertThat(existing("get", "/products").get("parameters").toString()).contains("\"availability\"");
        assertThat(spec.toString()).doesNotContain("\"issue\":\"#113\"");
        // #114 moved its planned changes into the customer definitions.
        assertThat(texts(schema("Customer").at("/properties/status/enum"))).contains("병합됨");
        assertThat(texts(schema("Customer").get("required"))).contains("mergedInto", "absorbedCustomerCount");
        assertThat(texts(schema("CustomerListItem").at("/properties/status/enum"))).contains("병합됨");
        for (String path : List.of("/payments", "/room-reservations", "/seat-sessions", "/locker-assignments")) {
            assertThat(existing("post", path).at("/responses/409/content/application~1json/schema/$ref").asString()).as(path)
                    .isEqualTo("#/components/schemas/OperationsConflictError");
        }
        assertThat(spec.toString()).doesNotContain("\"issue\":\"#114\"");
        for (String path : List.of("/payments/{id}/refund", "/room-reservations/{id}/cancel")) {
            assertThat(planned(existing("post", path), "#115").get("note").asString()).as(path).contains("daily_closing_adjustments");
        }
        // No implemented schema gained a field before its implementation.
        assertThat(names(schema("AggregationCondition").get("properties"))).doesNotContain("methods");
    }

    /** Acceptance 2: the three reasons a product cannot be sold are different codes, checked in the ADR order. */
    @Test void eachReasonAProductCannotBeSoldHasItsOwnCode() throws Exception {
        var payment = existing("post", "/payments");
        var codes = texts(payment.get("x-error-codes"));
        assertThat(codes).doesNotHaveDuplicates();
        // In the order of the check (M13-D06): stopped, branch, period, limit.
        assertThat(codes.subList(codes.indexOf("PRODUCT_NOT_ON_SALE"), codes.indexOf("PRODUCT_SALE_LIMIT_REACHED") + 1))
                .containsExactly("PRODUCT_NOT_ON_SALE", "PRODUCT_NOT_AVAILABLE_AT_BRANCH", "PRODUCT_NOT_IN_SALE_PERIOD",
                        "PRODUCT_SALE_LIMIT_REACHED");
        assertThat(payment.at("/responses/409/content/application~1json/schema/$ref").asString())
                .isEqualTo("#/components/schemas/OperationsConflictError");
        assertThat(payment.get("description").asString()).contains("판매 중지 → 판매 지점 → 판매 기간 → 남은 한도");
        assertThat(contract()).contains("| 1 | 판매 중지 | 409 `PRODUCT_NOT_ON_SALE`(기존) |",
                "| 3 | 처리 시각 < `salesStartsAt` 또는 처리 시각 ≥ `salesEndsAt` | 409 `PRODUCT_NOT_IN_SALE_PERIOD`",
                "| 4 | `salesLimit`이 있고 `soldQuantity` ≥ `salesLimit` | 409 `PRODUCT_SALE_LIMIT_REACHED`");
    }

    /** Acceptance 3: lists and aggregations take the same filter structure and report the filter they applied. */
    @Test void listsAndAggregationsShareTheAdvancedFilterAndReportIt() throws Exception {
        assertThat(plannedParameters("/payments", "#116")).containsAll(FILTERS)
                .contains("#/components/parameters/PassCategoryCode");
        for (String path : List.of("/sales/revenue", "/sales/revenue/branches", "/sales/ledger", "/sales/trend",
                "/sales/passes/volume", "/sales/passes/products")) {
            assertThat(plannedParameters(path, "#116")).as(path).containsAll(FILTERS);
        }
        // The same names in the applied condition of a list and of an aggregation.
        var condition = names(planned(schema("AggregationCondition"), "#116").get("properties"));
        assertThat(condition).contains("methods", "productKinds", "operatorIds", "processingBranchIds");
        assertThat(names(schema("PaymentFilter").get("properties"))).containsAll(condition.subList(0, 4))
                .contains("categoryCode");
        assertThat(names(planned(schema("AggregationMeta"), "#116").get("properties"))).containsExactly("conditionNotes");
        assertThat(names(planned(schema("PaymentPage"), "#116").get("properties"))).containsExactly("filter", "summary");
        assertThat(texts(schema("ConditionNote").at("/properties/code/enum"))).contains("PASS_ONLY",
                "REFUND_PROCESSING_BRANCH", "OPERATOR_BY_ENTRY", "REFUND_BY_PAYMENT_METHOD", "USAGE_NOT_REVENUE");
        assertThat(contract()).contains("목록(`GET /payments`)과 집계(`/sales/*`)가 **같은 이름·같은 의미**의 query를 쓴다");
        // The usage metrics are defined once, like every M9 metric, and are not revenue (M13-D12).
        var metrics = new ArrayList<String>();
        for (var metric : spec.get("x-metric-definitions")) {
            if (!metric.get("adr").toString().contains("M13-")) continue;
            metrics.add(metric.get("id").asString());
            for (String field : List.of("name", "unit", "definition", "includes", "excludes", "branchAttribution", "timeBasis",
                    "refundBasisApplies", "dimensions", "source", "operations", "adr")) {
                assertThat(metric.has(field)).as("%s %s", metric.get("id"), field).isTrue();
            }
            assertThat(texts(metric.get("operations"))).containsExactly("getPassUsageByBranch");
            assertThat(metric.get("refundBasisApplies").asBoolean()).isFalse();
            assertThat(schema("MetricUnit").get("enum").toString()).contains("\"" + metric.get("unit").asString() + "\"");
            assertThat(contract()).contains("| `" + metric.get("id").asString() + "` | " + metric.get("name").asString() + " | "
                    + metric.get("unit").asString() + " |");
        }
        assertThat(metrics).containsExactly("pass.usage_session_count", "pass.usage_minutes", "pass.usage_credit_krw");
    }

    /** Acceptance 4: the permission of each operation is the ADR's. */
    @Test void permissionsFollowTheAdr() throws Exception {
        for (String id : List.of("createBranchClosure", "updateBranchClosure", "deleteBranchClosure")) {
            assertThat(operation(id).get("x-permission").asString()).as(id).contains("MANAGER 이상", "M13-D05");
        }
        for (String id : List.of("listCustomerDuplicates", "mergeCustomer")) {
            assertThat(operation(id).get("x-permission").asString()).as(id).startsWith("OWNER");
        }
        assertThat(operation("createDailyClosing").get("x-permission").asString()).contains("MANAGER 이상", "M13-D04");
        for (String id : List.of("listDailyClosings", "getDailyClosing", "getPassUsageByBranch")) {
            assertThat(operation(id).get("x-permission").asString()).as(id).contains("sales:read");
        }
        assertThat(operation("getSeatOccupancyTrend").get("x-permission").asString()).isEqualTo("OWNER 또는 지점의 유효 소속");
        String adr = Files.readString(Path.of("docs/db/ADR-M13-001.md"));
        assertThat(adr).contains("| M13-D11 |", "| M13-D12 |", "| M13-D13 |", "2026-11-10 사용자 결정");
    }

    @Test void theContractIsPublishedAsTheM13Version() throws Exception {
        assertThat(spec.at("/info/version").asString()).isEqualTo("0.1.14-m13-operations-extension");
        assertThat(spec.at("/info/description").asString()).contains("Issue #111", "operations-extension-api.md",
                "x-planned-changes");
        assertThat(contract()).contains("`0.1.14-m13-operations-extension`", "operation은 모두 설계 계약(`design-target`)");
        assertThat(Files.readString(DESIGN.resolve("README.md"))).contains("operations-extension-api.md",
                "0.1.14-m13-operations-extension");
    }
}
