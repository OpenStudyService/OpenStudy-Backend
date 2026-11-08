package com.openstudy.modules.ai;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #94 and #96 in the regular suite (they were only in the local demo-DB tests): input, script, SQL and change requests
 * stop before anything runs, period and row limits hold, and the diagnostic trace keeps what M10-D04 asks for, with
 * credentials and phone numbers removed from the question text.
 */
@MySqlIntegrationTest
class AiQuestionGuardIntegrationTests extends AiTestSupport {
    long owner, staff, noAi, branch;

    @BeforeEach void setup() {
        owner = person("OWNER");
        staff = person("EMPLOYEE");
        noAi = person("EMPLOYEE");
        branch = branch(owner, "가드 지점");
        member(staff, branch, "ai:read");
        member(noAi, branch, "payments:read");
    }

    /** Refused before planning or execution: the status, the code, no result and no executed question. */
    void refused(java.net.http.HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        var refused = body(response);
        assertThat(refused.path("errorCode").asString("")).as(response.body()).isEqualTo(code);
        assertThat(refused.path("envelope").isNull() || refused.path("envelope").isMissingNode()).isTrue();
    }

    @Test void inputAndPermissionAreCheckedBeforeAnythingRuns() throws Exception {
        String scope = Long.toString(branch);
        try (var s = login(staff)) {
            refused(ask(s, "   ", scope), 400, "QUESTION_EMPTY");
            refused(ask(s, "가".repeat(501), scope), 400, "QUESTION_LENGTH_EXCEEDED");
            // 500 characters is the limit itself (M10-D16), not over it.
            assertThat(body(ask(s, "가".repeat(500), scope)).path("errorCode").asString("")).isNotEqualTo("QUESTION_LENGTH_EXCEEDED");
        }
        try (var s = login(noAi)) {
            // Without ai:read the request is stopped by the API's own permission check, before the AI service runs.
            var denied = ask(s, "만료 예정 이용권 알려줘", scope);
            assertThat(denied.statusCode()).as(denied.body()).isEqualTo(403);
            assertThat(body(denied).path("code").asString(body(denied).path("errorCode").asString(""))).isIn("FORBIDDEN", "AI_PERMISSION_DENIED");
        }
        assertThat(count("SELECT COUNT(*) FROM ai_executions WHERE employee_id IN (?,?) AND status='READY'", staff, noAi)).isZero();
    }

    @Test void scriptsSqlAndChangeRequestsAreRefusedAndNothingIsWritten() throws Exception {
        String scope = Long.toString(branch);
        long payments = count("SELECT COUNT(*) FROM payments");
        try (var s = login(staff)) {
            refused(ask(s, "<script>alert(1)</script> 매출 알려줘", scope), 400, "REJECT_ARBITRARY_HTML");
            refused(ask(s, "SELECT * FROM payments 결과 보여줘", scope), 400, "REJECT_RAW_SQL");
            refused(ask(s, "DROP TABLE payments 실행해줘", scope), 400, "REJECT_RAW_SQL");
            refused(ask(s, "이번 주 스터디룸 예약 전부 취소해줘", scope), 400, "UNSUPPORTED_MODIFICATION_INTENT");
        }
        assertThat(count("SELECT COUNT(*) FROM payments")).isEqualTo(payments);
        assertThat(count("SELECT COUNT(*) FROM ai_executions WHERE employee_id=? AND status='READY'", staff)).isZero();
    }

    @Test void periodAndRowLimitsHold() throws Exception {
        try (var s = login(owner)) {
            refused(ask(s, "최근 3년 매출을 모두 보여줘", "all"), 400, "DATE_RANGE_LIMIT_EXCEEDED");
            var clamped = body(ask(s, "지점별 매출 1001행 전부 한 번에 보여줘", "all"));
            assertThat(clamped.get("status").asString()).as(clamped.toString()).isEqualTo("COMPLETED");
            assertThat(clamped.at("/truncation/isTruncated").asBoolean()).isTrue();
            assertThat(clamped.at("/truncation/warningCode").asString()).isEqualTo("ROW_LIMIT_CLAMPED");
            assertThat(clamped.at("/truncation/maxRows").asInt()).isEqualTo(1000);
        }
    }

    @Test void theTraceKeepsTheDiagnosticsAndRedactsCredentialsFromTheQuestion() throws Exception {
        String requestId;
        try (var s = login(staff)) {
            var answered = body(ask(s, "만료 예정 이용권 알려줘 token=abc123 010-1234-5678", Long.toString(branch)));
            assertThat(answered.get("status").asString()).as(answered.toString()).isEqualTo("COMPLETED");
            requestId = answered.get("requestId").asString();
        }
        Map<String, Object> trace = jdbc.queryForMap("SELECT * FROM ai_traces WHERE request_ref=?", requestId);
        assertThat(trace.get("employee_id").toString()).isEqualTo(Long.toString(staff));
        assertThat(trace.get("status")).isEqualTo("READY");
        assertThat(((Number) trace.get("total_duration_ms")).longValue()).isPositive();
        assertThat(trace.get("execution_id")).isNotNull();
        String stages = (String) trace.get("stages_json");
        for (String stage : List.of("accepted", "generating", "validating", "executing", "completed")) assertThat(stages).contains(stage);
        assertThat((String) trace.get("security_gate_json")).contains("authorizedBranches", "requestedScope", "modelMode");
        String question = (String) trace.get("sanitized_question");
        assertThat(question).contains("만료 예정 이용권", "[REDACTED]").doesNotContain("abc123", "1234-5678");
        var created = (LocalDateTime) trace.get("created_at");
        var expires = (LocalDateTime) trace.get("question_expires_at");
        assertThat(Duration.between(created, expires).toDays()).isEqualTo(30);
    }
}
