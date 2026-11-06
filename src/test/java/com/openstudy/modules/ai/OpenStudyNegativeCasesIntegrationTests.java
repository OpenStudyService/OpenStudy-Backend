package com.openstudy.modules.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.openstudy.domainlens.api.ir.ViewSpec;
import com.openstudy.modules.ai.controller.AiWorkspaceController;
import com.openstudy.modules.ai.service.AiWorkspaceService.AiQueryRequest;
import com.openstudy.modules.ai.service.AiWorkspaceService.AiQueryResponse;
import com.openstudy.modules.ai.service.OpenStudyPlaybackGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 7 (`OS-DL-0703` & `OS-DL-0704`) end-to-end verification of `POC-001`..`POC-010`
 * (`expected_aggregations.json`, `expected_queryspec.json`, `expected_viewspec.json`)
 * and `NEG-001`..`NEG-018` (`negative_cases.json`) via `POST /api/ai/queries`.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:mysql://127.0.0.1:3307/openstudy?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "spring.datasource.username=openstudy_app",
                "spring.datasource.password=${OPENSTUDY_AI_TEST_DB_PASSWORD:}",
                "spring.flyway.enabled=true",
                "spring.flyway.url=jdbc:mysql://127.0.0.1:3307/openstudy?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "spring.flyway.user=openstudy_app",
                "spring.flyway.password=${OPENSTUDY_AI_TEST_DB_PASSWORD:}",
                "openstudy.ai.fixed-as-of=2026-11-02T05:00:00Z",
                "openstudy.ai.model-mode=playback"
        }
)
@ActiveProfiles("test")
@EnabledIf("com.openstudy.domainlens.LocalAiFixtures#localAiTestsEnabled")
class OpenStudyNegativeCasesIntegrationTests {

    @Autowired
    AiWorkspaceController controller;

    @Autowired
    OpenStudyPlaybackGateway playbackGateway;

    static JsonNode expectedQuerySpecs;
    static JsonNode expectedViewSpecs;
    static JsonNode expectedAggregations;
    static JsonNode negativeCases;

    @BeforeAll
    static void loadSpecs() throws Exception {
        var mapper = JsonMapper.builder().build();
        expectedQuerySpecs = mapper.readTree(Files.readString(resolveDocPath("docs/05_TEST_CASES/expected_queryspec.json"))).get("cases");
        expectedViewSpecs = mapper.readTree(Files.readString(resolveDocPath("docs/05_TEST_CASES/expected_viewspec.json"))).get("cases");
        expectedAggregations = mapper.readTree(Files.readString(resolveDocPath("docs/04_TEST_DATA/expected_aggregations.json"))).get("cases");
        negativeCases = mapper.readTree(Files.readString(resolveDocPath("docs/05_TEST_CASES/negative_cases.json"))).get("cases");
    }

    @Test
    void allTenPocQuestionsExecuteEndToEndAndMatchExpectedQuerySpecAndViewSpec() {
        Authentication ownerAuth = authForEmployee(1L);

        for (int i = 1; i <= 10; i++) {
            playbackGateway.resetRateLimits();
            String pocId = String.format("POC-%03d", i);
            JsonNode qsSpec = expectedQuerySpecs.get(pocId);
            JsonNode vsSpec = expectedViewSpecs.get(pocId);
            JsonNode aggSpec = expectedAggregations.get(pocId);

            String question = qsSpec.get("question").asString();
            String expectedDomain = qsSpec.get("intent_domain").asString();
            String expectedSemanticStatus = aggSpec.get("semantic_status").asString();

            ResponseEntity<AiQueryResponse> response = controller.executeQuery(
                    ownerAuth,
                    new AiQueryRequest(question, "all", null, null, null)
            );
            assertThat(response.getStatusCode().value()).as("%s HTTP status", pocId).isEqualTo(200);

            AiQueryResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.status()).as("%s status", pocId).isEqualTo("COMPLETED");
            assertThat(body.pocCode()).as("%s pocCode", pocId).isEqualTo(pocId);
            assertThat(body.intentDomain()).as("%s intentDomain", pocId).isEqualTo(expectedDomain);
            assertThat(body.semanticStatus()).as("%s semanticStatus", pocId).isEqualTo(expectedSemanticStatus);
            assertThat(body.envelope()).isNotNull();
            assertThat(body.viewSpec()).isNotNull();
            assertThat(body.stages()).hasSize(6);
            assertThat(body.retrievedCandidates()).isNotEmpty();

            List<String> actualComponents = body.viewSpec().components().stream()
                    .map(ViewSpec.Component::componentRef)
                    .map(ViewSpec.Ref::id)
                    .toList();
            for (JsonNode targetComp : vsSpec.get("target_components")) {
                assertThat(actualComponents)
                        .as("%s ViewSpec components must include %s", pocId, targetComp.asString())
                        .contains(targetComp.asString());
            }

            assertThat(aggSpec.get("question").asString()).isEqualTo(question);
        }
    }

    @Test
    void allEighteenNegativeCasesExecuteViaControllerAndMatchExpectedGuardrailOutcomes() {
        assertThat(negativeCases).hasSize(18);

        for (JsonNode node : negativeCases) {
            playbackGateway.resetRateLimits();
            String id = node.get("id").asString();
            String question = node.get("question").asString();
            String actor = node.get("actor").asString();
            String expectedCat = node.get("expected_result_category").asString();
            String expectedBehavior = node.get("expected_http_or_behavior").asString();

            Authentication auth = authForActorString(actor);
            ResponseEntity<AiQueryResponse> res = controller.executeQuery(
                    auth,
                    new AiQueryRequest(question, "all", null, null, actor)
            );
            AiQueryResponse body = res.getBody();
            assertThat(body).isNotNull();
            assertThat(body.resultCategory())
                    .as("%s (%s) resultCategory", id, question)
                    .isEqualTo(expectedCat);
            assertThat(body.behavior())
                    .as("%s (%s) behavior", id, question)
                    .isEqualTo(expectedBehavior);
        }
    }

    @Test
    void fullEvaluationSuiteEndpointVerifiesAllTwentyEightCasesAt100PercentPassRate() {
        Authentication ownerAuth = authForEmployee(1L);
        Map<String, Object> evalResult = controller.runEvaluationSuite(
                ownerAuth,
                new AiWorkspaceController.EvaluationRunRequest("ALL")
        );
        assertThat(evalResult.get("status")).isEqualTo("PASSED");
        assertThat(evalResult.get("totalCount")).isEqualTo(28);
        assertThat(evalResult.get("passedCount")).isEqualTo(28L);
        assertThat(evalResult.get("failedCount")).isEqualTo(0L);
        assertThat(evalResult.get("passRatePct")).isEqualTo(100.0);
    }

    private static Authentication authForEmployee(long employeeId) {
        return new UsernamePasswordAuthenticationToken(Long.toString(employeeId), "N/A", List.of());
    }

    private static Authentication authForActorString(String actor) {
        return switch (actor) {
            case "ACTIVE_OWNER" -> authForEmployee(1L);
            case "osdemo.gn.manager@example.invalid" -> authForEmployee(9_200_001L);
            case "osdemo.ss.manager@example.invalid" -> authForEmployee(9_200_002L);
            case "osdemo.gn.ai@example.invalid" -> authForEmployee(9_200_003L);
            case "osdemo.ss.noai@example.invalid" -> authForEmployee(9_200_004L);
            default -> authForEmployee(1L);
        };
    }

    private static Path resolveDocPath(String relative) {
        Path p1 = Path.of("..", relative);
        if (Files.exists(p1)) return p1;
        Path p2 = Path.of(relative);
        if (Files.exists(p2)) return p2;
        return Path.of("/Users/seeker/Projects/JavaProjects/OpenStudy", relative);
    }
}
