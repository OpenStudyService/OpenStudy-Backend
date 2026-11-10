package com.openstudy.architecture;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.openstudy.modules.ai.controller.AiWorkspaceController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #92: the AI chat operations are published in nl-analysis-api.md and openapi.json, under /api/ai only (the older
 * aliases and the ported records, approvals, traces and evaluation endpoints stay outside the contract, 2026-11-08
 * user decision), and every published route is one the controller really serves.
 */
class AiContractTests {
    static final Path DESIGN = Path.of("docs/design/foundation");
    static final Map<String, String> OPERATIONS = Map.of(
            "getAiReadiness", "get /ai/catalog/readiness",
            "createAiConversation", "post /ai/conversations",
            "listAiConversations", "get /ai/conversations",
            "reopenAiConversation", "get /ai/conversations/{conversationId}",
            "renameAiConversation", "patch /ai/conversations/{conversationId}",
            "deleteAiConversation", "delete /ai/conversations/{conversationId}",
            "askAiQuestion", "post /ai/queries",
            "submitAiConversationRequest", "post /ai/conversations/{conversationId}/requests",
            "cancelAiRequest", "post /ai/requests/{requestId}/cancel",
            "getAiExecution", "get /ai/executions/{executionId}");

    final JsonNode spec = read();

    static JsonNode read() {
        try {
            return JsonMapper.builder().build().readTree(Files.readString(DESIGN.resolve("openapi.json")));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String contract() throws Exception { return Files.readString(DESIGN.resolve("nl-analysis-api.md")); }

    JsonNode operation(String id) {
        String[] route = OPERATIONS.get(id).split(" ");
        return spec.get("paths").get(route[1]).get(route[0]);
    }

    @Test void theTenChatOperationsArePublishedAsImplemented() throws Exception {
        var published = new ArrayList<String>();
        spec.get("paths").properties().forEach(path -> path.getValue().properties().forEach(method -> {
            if (method.getValue().has("tags") && method.getValue().get("tags").toString().contains("\"ai\""))
                published.add(method.getKey() + " " + path.getKey());
        }));
        // M15 (#124) adds its own ai operations; they are checked by AnalysisReuseContractTests.
        published.removeAll(AnalysisReuseContractTests.OPERATIONS.values());
        assertThat(published).containsExactlyInAnyOrderElementsOf(OPERATIONS.values());
        String doc = contract();
        OPERATIONS.forEach((id, route) -> {
            var operation = operation(id);
            assertThat(operation.get("operationId").asString()).isEqualTo(id);
            assertThat(operation.get("x-implementation-status").asString()).as(id).isEqualTo("implemented");
            assertThat(doc).as(id).contains("`" + id + "`");
            if (!route.startsWith("get ")) {
                assertThat(operation.get("parameters").toString()).as(id + " CSRF").contains("#/components/parameters/Csrf");
            }
        });
        assertThat(spec.at("/info/description").asString()).contains("#92", "nl-analysis-api.md");
    }

    @Test void theOlderAliasesAndThePortedExtrasStayOutOfTheContract() throws Exception {
        for (String path : List.of("/conversations", "/conversations/{conversationId}/requests", "/requests/{requestId}/cancel",
                "/executions/{executionId}", "/catalog/readiness", "/ai/records", "/ai/approvals", "/ai/traces",
                "/ai/evaluation/suites", "/ai/evaluation/run")) {
            assertThat(spec.at("/paths/" + path.replace("~", "~0").replace("/", "~1")).isMissingNode()).as(path).isTrue();
        }
        assertThat(contract()).contains("2026-11-08에 서버에서 지웠다", "/api/ai/records", "/api/ai/approvals", "/api/ai/traces", "/api/ai/evaluation");
    }

    /** Every published route is served by the controller, so the contract cannot drift from the code. */
    @Test void everyPublishedRouteIsServedByTheController() {
        Set<String> served = new HashSet<>();
        for (Method method : AiWorkspaceController.class.getDeclaredMethods()) {
            add(served, "get", method.isAnnotationPresent(GetMapping.class) ? routes(method.getAnnotation(GetMapping.class).value(), method.getAnnotation(GetMapping.class).path()) : null);
            add(served, "post", method.isAnnotationPresent(PostMapping.class) ? routes(method.getAnnotation(PostMapping.class).value(), method.getAnnotation(PostMapping.class).path()) : null);
            add(served, "patch", method.isAnnotationPresent(PatchMapping.class) ? routes(method.getAnnotation(PatchMapping.class).value(), method.getAnnotation(PatchMapping.class).path()) : null);
            add(served, "delete", method.isAnnotationPresent(DeleteMapping.class) ? routes(method.getAnnotation(DeleteMapping.class).value(), method.getAnnotation(DeleteMapping.class).path()) : null);
        }
        OPERATIONS.values().forEach(route -> assertThat(served).as(route).contains(route));
    }

    static String[] routes(String[] value, String[] path) { return value.length > 0 ? value : path; }

    static void add(Set<String> served, String method, String[] routes) {
        if (routes == null) return;
        for (String route : routes) if (route.startsWith("/api/")) served.add(method + " " + route.substring(4));
    }

    @Test void everyErrorCodeAndReopenReasonIsDocumented() throws Exception {
        String doc = contract();
        OPERATIONS.keySet().forEach(id -> {
            for (var code : operation(id).get("x-error-codes")) assertThat(doc).as("%s %s", id, code).contains(code.asString());
        });
        assertThat(operation("reopenAiConversation").get("x-error-codes").toString())
                .contains("\"ANALYSIS_UNAVAILABLE\"", "\"REOPEN_NOT_EXECUTABLE\"");
        assertThat(doc).contains("모델을 호출하지 않는다", "결과 행·합계·건수는 저장하지 않는다", "UNSUPPORTED_MODIFICATION_INTENT");
    }

    /** Frontend #84 moved to envelope and viewSpec, so the older result shape is gone from the contract. */
    @Test void theOlderResultShapeIsGone() throws Exception {
        assertThat(spec.at("/components/schemas/AiLegacyExecution").isMissingNode()).isTrue();
        assertThat(spec.at("/components/schemas/AiQueryResponse/properties/legacyExecution").isMissingNode()).isTrue();
        assertThat(spec.at("/components/schemas/AiExecution").toString()).doesNotContain("\"rows\"", "AiLegacyExecution");
        assertThat(contract()).contains("옛 경로와 옛 형식 결과는 2026-11-08에 지웠다");
    }

    /** ADR-COM-001: a question is one request and one response; nothing streams. */
    @Test void aQuestionIsOneRequestAndOneResponse() throws Exception {
        OPERATIONS.keySet().forEach(id -> assertThat(operation(id).toString()).as(id).doesNotContain("text/event-stream"));
        assertThat(contract()).contains("ADR-COM-001", "SSE");
        var view = spec.at("/components/schemas/AiViewSpec/properties/components/items/properties/componentRef/properties/id/enum");
        assertThat(view.toString()).contains("\"KPI\"", "\"DATA_TABLE\"", "\"LINE_CHART\"");
    }

    /**
     * The document is OpenAPI 3.0.3, so a nullable field uses "nullable": true (with allOf for a $ref), never the 3.1
     * type array or a {"type": "null"} branch, which swagger-cli rejects.
     */
    @Test void nullableFieldsUseOpenApi30Syntax() {
        assertThat(spec.get("openapi").asString()).startsWith("3.0.");
        var offenders = new ArrayList<String>();
        collect3_1Nullables(spec, "#", offenders);
        assertThat(offenders).isEmpty();
        var execution = spec.at("/components/schemas/AiExecution/properties");
        for (String field : List.of("pocCode", "resultCategory", "querySpec"))
            assertThat(execution.get(field).get("nullable").asBoolean()).as(field).isTrue();
        assertThat(execution.at("/querySpec/allOf/0/$ref").asString()).isEqualTo("#/components/schemas/AiQuerySpec");
    }

    static void collect3_1Nullables(JsonNode node, String pointer, List<String> offenders) {
        if (node.isObject()) {
            JsonNode type = node.get("type");
            if (type != null && (type.isArray() || "null".equals(type.asString(""))))
                offenders.add(pointer);
            node.properties().forEach(e -> collect3_1Nullables(e.getValue(), pointer + "/" + e.getKey(), offenders));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) collect3_1Nullables(node.get(i), pointer + "/" + i, offenders);
        }
    }
}
