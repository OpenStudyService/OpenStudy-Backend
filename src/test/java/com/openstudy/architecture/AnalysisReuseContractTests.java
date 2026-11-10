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
 * Issue #124: the published M15 contract defines filter refinement, recent queries, result paging, follow-up context and
 * CSV export on top of the registered-analysis M10, with their errors, the rule that every reuse path checks permissions
 * again, and the CSV format (header block, encoding, row cap, formula guard).
 */
class AnalysisReuseContractTests {
    static final Path DESIGN = Path.of("docs/design/foundation");
    /** operationId → "method path", in the order of the contract document. */
    static final Map<String, String> OPERATIONS = new LinkedHashMap<>();
    static {
        OPERATIONS.put("refineAiExecution", "post /ai/executions/{executionId}/refinements");
        OPERATIONS.put("listAiRecentQueries", "get /ai/recent-queries");
        OPERATIONS.put("exportAiExecution", "get /ai/executions/{executionId}/export");
    }
    static final Map<String, String> ALL = new LinkedHashMap<>(OPERATIONS);
    static {
        ALL.put("exportSalesReport", "get /sales/exports/{report}");
    }
    static final Map<String, String> ISSUES = Map.of("refineAiExecution", "#125", "listAiRecentQueries", "#125",
            "exportAiExecution", "#127", "exportSalesReport", "#127");

    final JsonNode spec = read();

    static JsonNode read() {
        try {
            return JsonMapper.builder().build().readTree(Files.readString(DESIGN.resolve("openapi.json")));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String contract() throws java.io.IOException { return Files.readString(DESIGN.resolve("analysis-reuse-api.md")); }

    JsonNode operation(String id) {
        String[] route = ALL.get(id).split(" ");
        return spec.get("paths").get(route[1]).get(route[0]);
    }

    JsonNode schema(String name) { return spec.at("/components/schemas/" + name); }

    static List<String> texts(JsonNode array) {
        var values = new ArrayList<String>();
        for (var value : array) values.add(value.asString());
        return values;
    }

    /** Acceptance 1: every operation and its errors (permission loss, uneditable field, no plan expiry) are defined. */
    @Test void everyOperationIsDefinedWithItsErrorsAndDocumented() throws Exception {
        String contract = contract();
        String common = Files.readString(DESIGN.resolve("api.md")) + Files.readString(DESIGN.resolve("nl-analysis-api.md"));
        ALL.forEach((id, route) -> {
            var operation = operation(id);
            assertThat(operation).as(route).isNotNull();
            assertThat(operation.get("operationId").asString()).isEqualTo(id);
            for (String status : List.of("401", "403", "404", "422", "429", "500"))
                assertThat(operation.get("responses").has(status)).as("%s %s", id, status).isTrue();
            assertThat(operation.get("x-permission").asString()).as(id).isNotBlank();
            assertThat(operation.get("x-adr-decisions").toString()).as(id).contains("M15-D");
            assertThat(operation.get("x-implementation-status").asString()).as(id).isEqualTo("design-target");
            assertThat(operation.get("x-implementation-issue").asString()).as(id).isEqualTo(ISSUES.get(id));
            String[] parts = route.split(" ");
            assertThat(contract).as(route).contains("`" + parts[0].toUpperCase() + " " + parts[1]);
            for (var code : operation.get("x-error-codes"))
                assertThat(contract + common).as("%s %s", id, code).contains(code.asString());
            boolean write = !parts[0].equals("get");
            assertThat(operation.get("parameters").toString().contains("#/components/parameters/Csrf")).as(id).isEqualTo(write);
        });
        assertThat(texts(operation("refineAiExecution").get("x-error-codes"))).contains("FILTER_NOT_EDITABLE", "NO_FILTER_CHANGE",
                "PERIOD_TOO_LONG", "BRANCH_SCOPE_DENIED", "SALES_PERMISSION_DENIED", "NOT_EXECUTABLE", "ANALYSIS_UNAVAILABLE");
        assertThat(contract).contains("| 422 | `FILTER_NOT_EDITABLE` |", "| 422 | `NO_FILTER_CHANGE` |", "| 404 | `EXPORT_REPORT_UNKNOWN` |",
                "\"계획 만료\" 오류는 없다", "모델을 호출하지 않는다");
        // The M15 ai operations are not part of the ten M10 ones but carry the same tag.
        assertThat(operation("refineAiExecution").get("tags").toString()).contains("\"ai\"");
    }

    /** Acceptance 2: a follow-up answer tells inherited conditions from changed ones, and why the context was cut. */
    @Test void followUpAnswersSeparateInheritedAndChangedConditions() {
        var context = schema("AiContext").get("properties");
        assertThat(texts(schema("AiContext").get("required"))).contains("inherited", "changed", "reset", "resetReason");
        assertThat(texts(context.at("/inherited/items/enum"))).containsExactly("ANALYSIS", "PERIOD", "BRANCHES", "PRODUCTS",
                "REFUND_BASIS", "ZERO_FILL");
        assertThat(texts(context.at("/resetReason/enum"))).contains("SCOPE_CHANGED", "PERMISSION_LOST");
        var planned = schema("AiQueryResponse").get("x-planned-changes").toString();
        assertThat(planned).contains("#126", "AiContext", "AiConditions");
        assertThat(schema("AiExecution").get("x-planned-changes").toString()).contains("#125", "editableFilters", "REFINEMENT");
        assertThat(spec.at("/paths/~1ai~1executions~1{executionId}/get/x-planned-changes").toString())
                .contains("datasetId", "\"sort\"", "\"direction\"", "M15-D04");
        // A refinement takes only period, branches and products (M15-D05).
        var refinement = schema("AiRefinement");
        var fields = new ArrayList<String>();
        refinement.get("properties").properties().forEach(entry -> fields.add(entry.getKey()));
        assertThat(fields).containsExactlyInAnyOrder("period", "branchIds", "productIds", "clientRequestKey");
        assertThat(refinement.get("additionalProperties").asBoolean(true)).isFalse();
    }

    /** Acceptance 3: the CSV header block, encoding, row cap and formula guard are defined. */
    @Test void theCsvFormatIsDefined() throws Exception {
        String contract = contract();
        assertThat(contract).contains("UTF-8에 BOM(`EF BB BF`)", "CRLF", "RFC 4180", "조회 조건", "단위", "통화", "기준 시각",
                "최대 1,000행", "`=`, `+`, `-`, `@`", "작은따옴표", "`EXPORTED`");
        for (String id : List.of("exportAiExecution", "exportSalesReport"))
            assertThat(operation(id).at("/responses/200/content").has("text/csv")).as(id).isTrue();
        assertThat(texts(operation("exportSalesReport").at("/parameters/0/schema/enum"))).containsExactly("revenue", "revenue-branches",
                "ledger", "trend", "passes-volume", "passes-products", "passes-usage");
        // No paging on an export: the file holds up to 1,000 rows of the whole result.
        assertThat(operation("exportSalesReport").get("parameters").toString()).doesNotContain("parameters/Page\"");
    }

    /** Acceptance 4: the document states that every reuse path checks permissions again. */
    @Test void everyReusePathChecksPermissionsAgain() throws Exception {
        String contract = contract();
        assertThat(contract).contains("모든 재사용 경로는 권한을 다시 확인한다",
                "저장된 조건·문맥·화면에서 받은 값은 권한의 근거가 아니다", "문맥으로 권한을 우회하지 못하게");
        for (String id : List.of("refineAiExecution", "exportAiExecution"))
            assertThat(operation(id).get("x-permission").asString()).as(id).contains("다시 검사");
        String adr = Files.readString(Path.of("docs/db/ADR-M15-001.md"));
        assertThat(adr).contains("| M15-D09 |", "| M15-D10 |", "2026-11-11", "세션에 실행 계획을 30분 두지 않고");
    }

    @Test void theContractIsPublishedAsTheM15Version() throws Exception {
        assertThat(spec.at("/info/version").asString()).isEqualTo("0.1.16-m15-analysis-reuse");
        assertThat(spec.at("/info/description").asString()).contains("Issue #124", "analysis-reuse-api.md");
        assertThat(contract()).contains("`0.1.16-m15-analysis-reuse`", "operation은 모두 설계 계약(`design-target`)");
        assertThat(Files.readString(DESIGN.resolve("README.md"))).contains("analysis-reuse-api.md", "0.1.16-m15-analysis-reuse");
    }
}
