package com.openstudy.modules.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openstudy.modules.ai.controller.AiWorkspaceController;
import com.openstudy.modules.ai.service.AiWorkspaceService;
import com.openstudy.modules.ai.service.OpenStudyPlaybackGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Phase 5 integration tests (`OS-DL-0501`..`OS-DL-0505`) verifying:
 * <ul>
 *   <li>All 18 cases in {@code docs/05_TEST_CASES/negative_cases.json} ({@code NEG-001}..{@code NEG-018})</li>
 *   <li>{@code ADR-M10-001} branch scope, {@code ai:read}, {@code sales:read}, PII, budget, and rate-limit guardrails</li>
 *   <li>Flyway {@code V7__domainlens_ai_workspace.sql} persistence for conversations, executions, work records, CSV approvals, and traces</li>
 *   <li>Full compatibility with {@code Ai.tsx}/{@code domainlens.ts} and Stage 6 console endpoints</li>
 * </ul>
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
                "openstudy.ai.model-mode=playback",
                "openstudy.ai.rate-limit.max-concurrent=2",
                "openstudy.ai.rate-limit.max-per-minute=10"
        }
)
@ActiveProfiles("test")
@EnabledIf("com.openstudy.domainlens.LocalAiFixtures#localAiTestsEnabled")
class OpenStudyAiLifecycleAndNegativeCasesTests {

    @Autowired
    AiWorkspaceController controller;

    @Autowired
    AiWorkspaceService workspaceService;

    @Autowired
    OpenStudyPlaybackGateway playbackGateway;

    static JsonNode negativeCasesJson;

    @BeforeAll
    static void loadNegativeCasesJson() throws Exception {
        var mapper = JsonMapper.builder().build();
        Path negPath = resolveDocPath("docs/05_TEST_CASES/negative_cases.json");
        negativeCasesJson = mapper.readTree(Files.readString(negPath)).get("cases");
    }

    @BeforeEach
    void resetRateLimiter() {
        playbackGateway.resetRateLimits();
    }

    private static Path resolveDocPath(String relative) {
        for (Path candidate : List.of(
                Path.of(relative),
                Path.of("..", relative),
                Path.of("../..", relative)
        )) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot find document file: " + relative);
    }

    /** The negative cases name their actor; the request runs as that signed-in employee (the body actor is ignored). */
    private static Authentication authForActorString(String actor) {
        return switch (actor) {
            case "osdemo.gn.manager@example.invalid" -> authForEmployee(9_200_001L);
            case "osdemo.ss.manager@example.invalid" -> authForEmployee(9_200_002L);
            case "osdemo.gn.ai@example.invalid" -> authForEmployee(9_200_003L);
            case "osdemo.ss.noai@example.invalid" -> authForEmployee(9_200_004L);
            default -> authForEmployee(1L);
        };
    }

    private static Authentication authForEmployee(long employeeId) {
        return new UsernamePasswordAuthenticationToken(Long.toString(employeeId), "N/A", List.of());
    }

    @Test
    void allEighteenNegativeCasesFromJsonMatchExpectedCategoriesAndBehaviors() {
        assertThat(negativeCasesJson.size()).isEqualTo(18);
        for (JsonNode negCase : negativeCasesJson) {
            playbackGateway.resetRateLimits();
            String id = negCase.get("id").asString();
            String question = negCase.get("question").asString();
            String actor = negCase.get("actor").asString();
            String expectedCategory = negCase.get("expected_result_category").asString();
            String expectedBehavior = negCase.get("expected_http_or_behavior").asString();

            ResponseEntity<AiWorkspaceService.AiQueryResponse> httpRes = controller.executeQuery(
                    authForActorString(actor),
                    new AiWorkspaceService.AiQueryRequest(question, "all", null, null, actor)
            );
            AiWorkspaceService.AiQueryResponse body = httpRes.getBody();
            assertThat(body).as("Response body for %s", id).isNotNull();
            assertThat(body.resultCategory())
                    .as("Result category for %s (%s)", id, question)
                    .isEqualTo(expectedCategory);
            assertThat(body.behavior())
                    .as("Behavior for %s (%s)", id, question)
                    .isEqualTo(expectedBehavior);
        }
    }

    @Test
    void roleAndBranchScopeGuardrailsEnforceAiReadSalesReadAndNoSilentBranchDrop() {
        // 1. Employee 9200004 (osdemo.ss.noai@example.invalid) lacks ai:read -> 403 AI_PERMISSION_DENIED
        var noAiRes = controller.executeQuery(
                authForEmployee(9_200_004L),
                new AiWorkspaceService.AiQueryRequest("성수점 이번 달 매출 알려줘", "9100002", null, null, null)
        );
        assertThat(noAiRes.getStatusCode().value()).isEqualTo(403);
        assertThat(noAiRes.getBody().errorCode()).isEqualTo("AI_PERMISSION_DENIED");

        // 2. Employee 9200003 (osdemo.gn.ai@example.invalid) has ai:read on Gangnam (9100001) but lacks sales:read
        var gnAiSalesRes = controller.executeQuery(
                authForEmployee(9_200_003L),
                new AiWorkspaceService.AiQueryRequest("강남점 이번 달 매출을 비교해줘", "9100001", null, null, null)
        );
        assertThat(gnAiSalesRes.getStatusCode().value()).isEqualTo(403);
        assertThat(gnAiSalesRes.getBody().errorCode()).isEqualTo("SALES_PERMISSION_DENIED");

        // Non-sales question on Gangnam (9100001) for 9200003 succeeds!
        var gnAiSeatsRes = controller.executeQuery(
                authForEmployee(9_200_003L),
                new AiWorkspaceService.AiQueryRequest("지금 강남점에 빈자리가 얼마나 있어?", "9100001", null, null, null)
        );
        assertThat(gnAiSeatsRes.getStatusCode().value()).isEqualTo(200);
        assertThat(gnAiSeatsRes.getBody().status()).isEqualTo("COMPLETED");
        assertThat(gnAiSeatsRes.getBody().pocCode()).isEqualTo("POC-004");

        // 3. Employee 9200001 (Gangnam manager) explicitly asking about Seongsu (9100002) -> 403 BRANCH_SCOPE_DENIED
        var crossBranchRes = controller.executeQuery(
                authForEmployee(9_200_001L),
                new AiWorkspaceService.AiQueryRequest("성수점 이번 달 매출 알려줘", "9100001", null, null, null)
        );
        assertThat(crossBranchRes.getStatusCode().value()).isEqualTo(403);
        assertThat(crossBranchRes.getBody().errorCode()).isEqualTo("BRANCH_SCOPE_DENIED");

        // 4. Zero-sales branch 9100003 (신규점) returns normal 200 OK with 0 KRW sales, not an error
        var zeroSalesRes = controller.executeQuery(
                authForEmployee(1L),
                new AiWorkspaceService.AiQueryRequest("신규점 이번 달 매출 알려줘", "9100003", null, null, null)
        );
        assertThat(zeroSalesRes.getStatusCode().value()).isEqualTo(200);
        assertThat(zeroSalesRes.getBody().status()).isEqualTo("COMPLETED");
        @SuppressWarnings("unchecked")
        var totals = (Map<String, Long>) zeroSalesRes.getBody().summary().get("totals");
        assertThat(totals.get("net_krw")).isEqualTo(0L);
    }

    @Test
    void inputValidationReadOnlyPiiClarificationAndRateLimitGuardrailsWorkCorrectly() {
        Authentication ownerAuth = authForEmployee(1L);

        // Blank question -> 400 QUESTION_EMPTY
        var emptyRes = controller.executeQuery(
                ownerAuth,
                new AiWorkspaceService.AiQueryRequest("   ", "all", null, null, null)
        );
        assertThat(emptyRes.getStatusCode().value()).isEqualTo(400);
        assertThat(emptyRes.getBody().errorCode()).isEqualTo("QUESTION_EMPTY");

        // > 500 chars question -> 400 QUESTION_LENGTH_EXCEEDED
        String longQ = "매출 ".repeat(200);
        var tooLongRes = controller.executeQuery(
                ownerAuth,
                new AiWorkspaceService.AiQueryRequest(longQ, "all", null, null, null)
        );
        assertThat(tooLongRes.getStatusCode().value()).isEqualTo(400);
        assertThat(tooLongRes.getBody().errorCode()).isEqualTo("QUESTION_LENGTH_EXCEEDED");

        // Modification intent -> 400 UNSUPPORTED_MODIFICATION_INTENT
        var modRes = controller.executeQuery(
                ownerAuth,
                new AiWorkspaceService.AiQueryRequest("이번 주 스터디룸 예약 전부 취소해줘", "all", null, null, null)
        );
        assertThat(modRes.getStatusCode().value()).isEqualTo(400);
        assertThat(modRes.getBody().errorCode()).isEqualTo("UNSUPPORTED_MODIFICATION_INTENT");

        // Employee credentials / chat / inquiry raw text -> 403
        var credRes = controller.executeQuery(
                ownerAuth,
                new AiWorkspaceService.AiQueryRequest("직원 비밀번호 해시와 이메일 목록 보여줘", "all", null, null, null)
        );
        assertThat(credRes.getStatusCode().value()).isEqualTo(403);
        assertThat(credRes.getBody().errorCode()).isEqualTo("CREDENTIAL_ACCESS_DENIED");

        var chatRes = controller.executeQuery(
                ownerAuth,
                new AiWorkspaceService.AiQueryRequest("팀 채팅에서 무슨 대화 했는지 보여줘", "all", null, null, null)
        );
        assertThat(chatRes.getStatusCode().value()).isEqualTo(403);
        assertThat(chatRes.getBody().errorCode()).isEqualTo("FORBIDDEN_SOURCE_DENIED");

        // Ambiguous queries -> CLARIFICATION with selectable options
        for (String ambiguous : List.of("매출 보여줘", "어제 룸 예약이 왜 취소됐어?", "좌석 현황 알려줘")) {
            playbackGateway.resetRateLimits();
            var clarRes = controller.executeQuery(
                    ownerAuth,
                    new AiWorkspaceService.AiQueryRequest(ambiguous, "all", null, null, null)
            );
            assertThat(clarRes.getStatusCode().value()).isEqualTo(200);
            assertThat(clarRes.getBody().status()).isEqualTo("CLARIFICATION");
            assertThat(clarRes.getBody().clarificationOptions()).isNotEmpty();
        }

        // Per-user rate limit: > 10 requests per minute -> 429 RATE_LIMITED + Retry-After header
        playbackGateway.resetRateLimits();
        for (int i = 0; i < 10; i++) {
            var okRes = controller.executeQuery(
                    ownerAuth,
                    new AiWorkspaceService.AiQueryRequest("이번 달 지점별 매출을 비교해줘", "all", null, null, null)
            );
            assertThat(okRes.getStatusCode().value()).isEqualTo(200);
        }
        var rateLimitedRes = controller.executeQuery(
                ownerAuth,
                new AiWorkspaceService.AiQueryRequest("이번 달 지점별 매출을 비교해줘", "all", null, null, null)
        );
        assertThat(rateLimitedRes.getStatusCode().value()).isEqualTo(429);
        assertThat(rateLimitedRes.getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(rateLimitedRes.getBody().errorCode()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void fullAiWorkspaceLifecycleCoversConversationsSseWorkRecordsCsvApprovalsTracesAndEvaluations() {
        Authentication ownerAuth = authForEmployee(1L);

        // 1. Readiness check
        Map<String, Object> readiness = controller.readiness();
        assertThat(readiness.get("ready")).isEqualTo(true);
        assertThat(readiness.get("sourcesCount")).isEqualTo(17);
        assertThat(readiness.get("metricsCount")).isEqualTo(10);

        // 2. Legacy M10 conversation + request + SSE + execution contract (Ai.tsx / domainlens.ts)
        Map<String, Object> conv = controller.createConversation(
                ownerAuth,
                new AiWorkspaceController.ConversationCreateRequest("all", "매출 점검 대화")
        );
        String convId = (String) conv.get("id");
        assertThat(convId).startsWith("conv-");

        var reqEntity = controller.submitConversationRequest(
                ownerAuth,
                convId,
                new AiWorkspaceController.ConversationQueryRequest("이번 달 지점별 매출을 비교해줘", "all", null, null)
        );
        assertThat(reqEntity.getStatusCode().value()).isEqualTo(200);
        String requestId = (String) reqEntity.getBody().get("requestId");
        String executionId = (String) reqEntity.getBody().get("executionId");
        assertThat(requestId).startsWith("req-");
        assertThat(executionId).startsWith("exec-");

        var sseEmitter = controller.streamEvents(requestId);
        assertThat(sseEmitter).isNotNull();

        Map<String, Object> execContract = controller.getExecution(ownerAuth, executionId);
        assertThat(execContract.get("id")).isEqualTo(executionId);
        assertThat(execContract.get("status")).isEqualTo("READY");
        assertThat(execContract.get("currency")).isEqualTo("KRW");
        assertThat(execContract.get("timeZone")).isEqualTo("Asia/Seoul");
        assertThat((List<?>) execContract.get("rows")).hasSize(3);

        // 3. Work Records creation, listing, and permission re-verification on reopen
        Map<String, Object> savedRecord = controller.createWorkRecord(
                ownerAuth,
                new AiWorkspaceController.WorkRecordCreateRequest(executionId, "11월 지점별 매출 비교 보고", true)
        );
        String recordId = (String) savedRecord.get("id");
        assertThat(recordId).startsWith("rec-");
        assertThat(savedRecord.get("pinned")).isEqualTo(true);

        List<Map<String, Object>> records = controller.listWorkRecords(ownerAuth);
        assertThat(records).extracting(m -> m.get("id")).contains(recordId);

        Map<String, Object> reopened = controller.getWorkRecord(ownerAuth, recordId);
        assertThat(reopened.get("snapshot")).isNotNull();

        // Unauthorized employee (9200001 Gangnam manager lacks Seongsu 9100002 in this record) is denied on reopen!
        assertThatThrownBy(() -> controller.getWorkRecord(authForEmployee(9_200_001L), recordId))
                .isInstanceOf(AccessDeniedException.class);

        // 4. CSV Export Approval workflow (Request -> Approve -> Download CSV without PII)
        Map<String, Object> approval = controller.createApproval(
                ownerAuth,
                new AiWorkspaceController.ApprovalCreateRequest(executionId, "branchSales", "월간 정산 보고용 CSV 반출")
        );
        String approvalId = (String) approval.get("id");
        assertThat(approval.get("status")).isEqualTo("PENDING");

        Map<String, Object> decided = controller.decideApproval(
                ownerAuth,
                approvalId,
                new AiWorkspaceController.ApprovalDecisionRequest(true, "APPROVE", "승인 완료")
        );
        assertThat(decided.get("status")).isEqualTo("APPROVED");

        ResponseEntity<byte[]> csvResponse = controller.downloadApprovalCsv(ownerAuth, approvalId);
        assertThat(csvResponse.getStatusCode().value()).isEqualTo(200);
        String csvText = new String(csvResponse.getBody());
        assertThat(csvText).contains("\"branch_id\",\"branch_name\",\"approved_krw\",\"refund_krw\",\"net_krw\"");
        assertThat(csvText).contains("강남점 (데모)");
        assertThat(csvText).doesNotContain("password_hash", "customer_id");

        // 5. Execution Traces (6-stage spans, ONNX retrieval candidates, 30-day question expiry)
        List<Map<String, Object>> traces = controller.listTraces(ownerAuth);
        assertThat(traces).isNotEmpty();
        String traceId = (String) traces.getFirst().get("id");
        Map<String, Object> traceDetail = controller.getTrace(ownerAuth, traceId);
        assertThat(traceDetail.get("stages")).isNotNull();
        assertThat(traceDetail.get("retrievalCandidates")).isNotNull();
        assertThat(traceDetail.get("securityGate")).isNotNull();
        assertThat((String) traceDetail.get("questionExpiresAt")).startsWith("2026-12-02");

        // 6. Quality Evaluation Center (28 cases: 10 Golden + 18 Negative = 100% pass rate)
        List<Map<String, Object>> suites = controller.listEvaluationSuites();
        assertThat(suites).hasSize(3);

        Map<String, Object> evalRun = controller.runEvaluationSuite(
                ownerAuth,
                new AiWorkspaceController.EvaluationRunRequest("ALL")
        );
        assertThat(evalRun.get("totalCount")).isEqualTo(28);
        assertThat(evalRun.get("passedCount")).isEqualTo(28L);
        assertThat(evalRun.get("failedCount")).isEqualTo(0L);
        assertThat(evalRun.get("passRatePct")).isEqualTo(100.0);
        assertThat(evalRun.get("status")).isEqualTo("PASSED");
    }
}
