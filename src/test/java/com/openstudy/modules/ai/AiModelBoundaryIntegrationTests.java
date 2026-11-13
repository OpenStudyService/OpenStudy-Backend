package com.openstudy.modules.ai;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import com.openstudy.modules.ai.service.OpenStudyAnalyses;
import com.openstudy.modules.customer.service.CustomerService;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #93 and #96 with the model call in live mode against a local stand-in: what goes out to the model is only the
 * question, the scope and catalog candidate IDs (no stored rows, no customer data), and a model failure ends as an
 * ordinary answer or refusal without touching business data.
 */
@MySqlIntegrationTest
class AiModelBoundaryIntegrationTests extends AiTestSupport {
    static final HttpServer MODEL;
    static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    static final AtomicInteger STATUS = new AtomicInteger(200);
    static final AtomicReference<String> REPLY = new AtomicReference<>("");

    static {
        try {
            MODEL = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        MODEL.createContext("/v1/chat/completions", exchange -> {
            REQUESTS.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = REPLY.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(STATUS.get(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) exchange.getResponseBody().write(out);
            exchange.close();
        });
        MODEL.start();
    }

    @DynamicPropertySource
    static void liveModel(DynamicPropertyRegistry registry) {
        registry.add("openstudy.ai.model-mode", () -> "live");
        registry.add("domainlens.model.api-key", () -> "test-only-key");
        registry.add("domainlens.model.base-url", () -> "http://127.0.0.1:" + MODEL.getAddress().getPort() + "/v1");
    }

    @AfterAll static void stop() { MODEL.stop(0); }

    @Autowired CustomerService customerService;

    long owner, staff, branch;
    final String customerName = "모델비노출" + UUID.randomUUID().toString().substring(0, 6);

    @BeforeEach void setup() {
        REQUESTS.clear();
        STATUS.set(200);
        owner = person("OWNER");
        staff = person("EMPLOYEE");
        branch = branch(owner, "모델 경계 지점");
        member(staff, branch, "ai:read");
        var body = new HashMap<String, Object>();
        body.put("registeredBranchId", Long.toString(branch)); body.put("name", customerName); body.put("phone", "01098765432");
        customers.add(Long.parseLong(customerService.create(owner, "all", UUID.randomUUID().toString(), body, "ai-model-boundary").id()));
    }

    static String plan(String analysisId) {
        String content = "{\"outcome\":\"EXECUTE\",\"analysisId\":\"" + analysisId + "\",\"intentDomain\":\"passes\","
                + "\"refundBasisOverride\":null,\"zeroFillOverrideRequested\":false,\"message\":null}";
        return "{\"choices\":[{\"message\":{\"content\":" + quote(content) + "}}],\"usage\":{\"total_tokens\":12}}";
    }

    static String quote(String raw) { return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    @Test void onlyTheQuestionScopeAndCatalogCandidatesGoToTheModel() throws Exception {
        REPLY.set(plan(OpenStudyAnalyses.ID_POC_006));
        try (var s = login(staff)) {
            var answered = body(ask(s, "곧 끝나는 이용권 목록", Long.toString(branch)));
            assertThat(answered.get("status").asString()).as(answered.toString()).isEqualTo("COMPLETED");
            assertThat(answered.get("analysisId").asString()).isEqualTo(OpenStudyAnalyses.ID_POC_006);
        }
        assertThat(REQUESTS).hasSize(1);
        JsonNode sent = json.readTree(REQUESTS.getFirst());
        assertThat(sent.get("messages")).hasSize(2);
        assertThat(sent.at("/messages/0/role").asString()).isEqualTo("system");
        String user = sent.at("/messages/1/content").asString();
        assertThat(user).matches("(?s)scope=\\d+\\nretrievedCandidates=[^\\n]*\\nquestion=곧 끝나는 이용권 목록");
        // No stored row, customer or credential reaches the model.
        assertThat(REQUESTS.getFirst()).doesNotContain(customerName, "01098765432", "password", "rows", "approved_krw");
        // The model may only name a registered analysis.
        assertThat(sent.at("/response_format/json_schema/schema/properties/analysisId/enum").toString())
                .contains(OpenStudyAnalyses.ID_POC_001, OpenStudyAnalyses.ID_POC_010);
    }

    /**
     * #233: a general-metric question ("결제 금액") skips the registered-analysis picker, which would fit it onto net
     * sales, and goes straight to the generation path. Only the generation request reaches the model.
     */
    @Test void aGeneralMetricQuestionSkipsTheRegisteredAnalysisPicker() throws Exception {
        REPLY.set("{\"choices\":[{\"message\":{\"content\":\"not json\"}}]}");
        try (var s = login(owner)) {
            var answered = ask(s, "이번 달 결제 금액을 지점별로 보여줘", "all");
            assertThat(answered.statusCode()).as(answered.body()).isLessThan(500);
            assertThat(body(answered).get("analysisId").isNull()).as(answered.body()).isTrue();
        }
        assertThat(REQUESTS).isNotEmpty();
        assertThat(REQUESTS).noneMatch(request -> request.contains("retrievedCandidates="));
    }

    @Test void aModelFailureEndsAsAnOrdinaryAnswerAndChangesNoData() throws Exception {
        long payments = count("SELECT COUNT(*) FROM payments");
        STATUS.set(500);
        REPLY.set("{\"error\":\"down\"}");
        try (var s = login(staff)) {
            var failed = ask(s, "만료 예정 이용권 알려줘", Long.toString(branch));
            assertThat(failed.statusCode()).as(failed.body()).isLessThan(500);
            assertThat(body(failed).get("status").asString()).isEqualTo("COMPLETED");
            STATUS.set(200);
            REPLY.set("{\"choices\":[{\"message\":{\"content\":\"not json\"}}]}");
            var garbled = ask(s, "곧 끝나는 이용권 목록", Long.toString(branch));
            assertThat(garbled.statusCode()).as(garbled.body()).isLessThan(500);
            assertThat(body(garbled).get("envelope").isNull() || body(garbled).get("status").asString().equals("COMPLETED")).isTrue();
        }
        // The garbled question found no registered analysis, so the generation path (#233) asked the model once more;
        // its garbled plan also ended as an ordinary answer.
        assertThat(REQUESTS).hasSize(3);
        assertThat(count("SELECT COUNT(*) FROM payments")).isEqualTo(payments);
    }
}
