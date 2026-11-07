package com.openstudy.modules.ai.controller;

import com.openstudy.modules.ai.service.AiWorkspaceService;
import com.openstudy.modules.ai.service.OpenStudyPlaybackGateway;
import com.openstudy.modules.ai.service.OpenStudyUserContextResolver;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified REST controller for OpenStudy M10 DomainLens AI Workspace (`OS-DL-0504`).
 * Supports both the legacy M10 frontend contract (`Ai.tsx` / `domainlens.ts`) and the Stage 6 5-view console
 * (`/api/ai/queries`, `/api/ai/records`, `/api/ai/approvals`, `/api/ai/traces`, `/api/ai/evaluation`).
 */
@RestController
public class AiWorkspaceController {

    public record ConversationCreateRequest(String scope, String title) {}

    public record ConversationQueryRequest(String question, String scope, String clarificationChoice, String actor) {}

    public record WorkRecordCreateRequest(String executionId, String title, Boolean pinned) {}

    public record ApprovalCreateRequest(String executionId, String datasetId, String reason) {}

    public record ApprovalDecisionRequest(Boolean approved, String decision, String note) {}

    public record EvaluationRunRequest(String suiteId) {}

    private final AiWorkspaceService workspaceService;
    private final OpenStudyPlaybackGateway playbackGateway;
    private final OpenStudyUserContextResolver userResolver;

    public AiWorkspaceController(
            AiWorkspaceService workspaceService,
            OpenStudyPlaybackGateway playbackGateway,
            OpenStudyUserContextResolver userResolver
    ) {
        this.workspaceService = workspaceService;
        this.playbackGateway = playbackGateway;
        this.userResolver = userResolver;
    }

    @GetMapping({"/api/catalog/readiness", "/api/ai/catalog/readiness"})
    public Map<String, Object> readiness() {
        return workspaceService.readiness();
    }

    @PostMapping({"/api/conversations", "/api/ai/conversations"})
    public Map<String, Object> createConversation(
            Authentication auth,
            @RequestBody(required = false) ConversationCreateRequest body
    ) {
        return workspaceService.createConversation(
                auth,
                body == null ? "all" : body.scope(),
                body == null ? null : body.title()
        );
    }

    /** The signed-in employee's saved AI conversations, most recently asked first. */
    @GetMapping("/api/ai/conversations")
    public List<Map<String, Object>> listConversations(Authentication auth) {
        return workspaceService.listConversations(auth);
    }

    /** Reopens one conversation: saved questions re-run under current permissions and a new as-of time (M10-D12). */
    @GetMapping("/api/ai/conversations/{conversationId}")
    public Map<String, Object> reopenConversation(
            Authentication auth,
            @PathVariable String conversationId,
            @RequestParam(required = false) String scope
    ) {
        return workspaceService.reopenConversation(auth, conversationId, scope);
    }

    public record ConversationRenameRequest(String title) {}

    @PatchMapping("/api/ai/conversations/{conversationId}")
    public Map<String, Object> renameConversation(
            Authentication auth,
            @PathVariable String conversationId,
            @RequestBody ConversationRenameRequest body
    ) {
        return workspaceService.renameConversation(auth, conversationId, body == null ? null : body.title());
    }

    @DeleteMapping("/api/ai/conversations/{conversationId}")
    public ResponseEntity<Void> deleteConversation(Authentication auth, @PathVariable String conversationId) {
        workspaceService.deleteConversation(auth, conversationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping({"/api/conversations/{conversationId}/requests", "/api/ai/conversations/{conversationId}/requests"})
    public ResponseEntity<Map<String, Object>> submitConversationRequest(
            Authentication auth,
            @PathVariable String conversationId,
            @RequestBody ConversationQueryRequest body
    ) {
        var actor = userResolver.resolve(auth, body == null ? null : body.actor());
        if (!playbackGateway.tryAcquireRateLimit(actor.employeeId())) {
            return ResponseEntity.status(429)
                    .header(HttpHeaders.RETRY_AFTER, "60")
                    .body(Map.of(
                            "code", "RATE_LIMITED",
                            "message", "질의 요청 빈도 제한(분당 10회, 동시 2건)을 초과했습니다. 잠시 후 다시 시도해 주세요."
                    ));
        }
        try {
            var response = workspaceService.executeQuery(auth, new AiWorkspaceService.AiQueryRequest(
                    body == null ? null : body.question(),
                    body == null ? "all" : body.scope(),
                    body == null ? null : body.clarificationChoice(),
                    conversationId,
                    body == null ? null : body.actor()
            ));
            if (response.httpStatus() >= 400) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("code", response.errorCode() == null ? "REQUEST_FAILED" : response.errorCode());
                err.put("message", response.message() == null ? "요청을 처리할 수 없습니다." : response.message());
                err.put("resultCategory", response.resultCategory());
                err.put("behavior", response.behavior());
                err.put("requestId", response.requestId());
                return ResponseEntity.status(response.httpStatus()).body(err);
            }
            Map<String, Object> accepted = new LinkedHashMap<>();
            accepted.put("requestId", response.requestId());
            accepted.put("executionId", response.executionId());
            accepted.put("conversationId", response.conversationId());
            accepted.put("status", response.status());
            return ResponseEntity.ok(accepted);
        } finally {
            playbackGateway.releaseRateLimit(actor.employeeId());
        }
    }

    @PostMapping("/api/ai/queries")
    public ResponseEntity<AiWorkspaceService.AiQueryResponse> executeQuery(
            Authentication auth,
            @RequestBody AiWorkspaceService.AiQueryRequest body
    ) {
        var actor = userResolver.resolve(auth, body == null ? null : body.actor());
        if (!playbackGateway.tryAcquireRateLimit(actor.employeeId())) {
            var rateLimited = new AiWorkspaceService.AiQueryResponse(
                    "req-ratelimited",
                    null,
                    body == null ? null : body.conversationId(),
                    null,
                    "RATE_LIMITED",
                    429,
                    "RATE_LIMITED",
                    "RATE_LIMITED",
                    "429",
                    "질의 요청 빈도 제한(분당 10회, 동시 2건)을 초과했습니다. 잠시 후 다시 시도해 주세요.",
                    null, null, null, null, null, null, null,
                    Map.of(),
                    OpenStudyPlaybackGateway.TruncationInfo.defaultUntruncated(),
                    List.of(),
                    List.of(),
                    List.of(),
                    Map.of("rateLimited", true),
                    null
            );
            return ResponseEntity.status(429)
                    .header(HttpHeaders.RETRY_AFTER, "60")
                    .body(rateLimited);
        }
        try {
            var response = workspaceService.executeQuery(auth, body);
            return ResponseEntity.status(response.httpStatus()).body(response);
        } finally {
            playbackGateway.releaseRateLimit(actor.employeeId());
        }
    }

    @PostMapping({"/api/requests/{requestId}/cancel", "/api/ai/requests/{requestId}/cancel"})
    public Map<String, Object> cancelRequest(@PathVariable String requestId) {
        return workspaceService.cancelRequest(requestId);
    }

    @GetMapping({"/api/executions/{executionId}", "/api/ai/executions/{executionId}"})
    public Map<String, Object> getExecution(Authentication auth, @PathVariable String executionId) {
        return workspaceService.getExecution(auth, executionId);
    }

    // =========================================================================
    // Work Records
    // =========================================================================

    @GetMapping("/api/ai/records")
    public List<Map<String, Object>> listWorkRecords(Authentication auth) {
        return workspaceService.listWorkRecords(auth);
    }

    @PostMapping("/api/ai/records")
    public Map<String, Object> createWorkRecord(
            Authentication auth,
            @RequestBody WorkRecordCreateRequest body
    ) {
        return workspaceService.createWorkRecord(
                auth,
                body == null ? null : body.executionId(),
                body == null ? null : body.title(),
                body != null && Boolean.TRUE.equals(body.pinned())
        );
    }

    @GetMapping("/api/ai/records/{recordId}")
    public Map<String, Object> getWorkRecord(Authentication auth, @PathVariable String recordId) {
        return workspaceService.getWorkRecord(auth, recordId);
    }

    // =========================================================================
    // CSV Export Approvals
    // =========================================================================

    @GetMapping("/api/ai/approvals")
    public List<Map<String, Object>> listApprovals(Authentication auth) {
        return workspaceService.listExportApprovals(auth);
    }

    @PostMapping("/api/ai/approvals")
    public Map<String, Object> createApproval(
            Authentication auth,
            @RequestBody ApprovalCreateRequest body
    ) {
        return workspaceService.requestExportApproval(
                auth,
                body == null ? null : body.executionId(),
                body == null ? null : body.datasetId(),
                body == null ? null : body.reason()
        );
    }

    @PostMapping("/api/ai/approvals/{approvalId}/decide")
    public Map<String, Object> decideApproval(
            Authentication auth,
            @PathVariable String approvalId,
            @RequestBody ApprovalDecisionRequest body
    ) {
        boolean approved = body != null && (Boolean.TRUE.equals(body.approved())
                || "APPROVE".equalsIgnoreCase(body.decision())
                || "APPROVED".equalsIgnoreCase(body.decision()));
        return workspaceService.decideExportApproval(
                auth,
                approvalId,
                approved,
                body == null ? null : body.note()
        );
    }

    @GetMapping(value = "/api/ai/approvals/{approvalId}/download", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> downloadApprovalCsv(
            Authentication auth,
            @PathVariable String approvalId
    ) {
        String csv = workspaceService.downloadApprovedCsv(auth, approvalId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"openstudy-export-" + approvalId + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    // =========================================================================
    // Diagnostic Traces
    // =========================================================================

    @GetMapping("/api/ai/traces")
    public List<Map<String, Object>> listTraces(Authentication auth) {
        return workspaceService.listTraces(auth);
    }

    @GetMapping("/api/ai/traces/{traceId}")
    public Map<String, Object> getTrace(Authentication auth, @PathVariable String traceId) {
        return workspaceService.getTrace(auth, traceId);
    }

    // =========================================================================
    // Quality Evaluation Center
    // =========================================================================

    @GetMapping("/api/ai/evaluation/suites")
    public List<Map<String, Object>> listEvaluationSuites() {
        return workspaceService.listEvaluationSuites();
    }

    @PostMapping("/api/ai/evaluation/run")
    public Map<String, Object> runEvaluationSuite(
            Authentication auth,
            @RequestBody(required = false) EvaluationRunRequest body
    ) {
        return workspaceService.runEvaluationSuite(auth, body == null ? "ALL" : body.suiteId());
    }
}
