package com.openstudy.modules.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.openstudy.support.database.MySqlIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.TestPropertySource;

/**
 * #233: one round trip through the real chat API with the live model (opt-in: OPENSTUDY_AI_LIVE_TESTS=true with
 * QWEN_API_KEY, QWEN_BASE_URL, QWEN_MODEL). It spends tokens: the registered planner asks the model first, then the
 * generation path plans the query. The response, the saved execution and a rerun are written to build/live-chat.txt.
 */
@MySqlIntegrationTest
@EnabledIfEnvironmentVariable(named = "OPENSTUDY_AI_LIVE_TESTS", matches = "true")
@TestPropertySource(properties = {"openstudy.ai.model-mode=live", "domainlens.model.mode=live"})
class AiGenerationLiveIntegrationTests extends AiTestSupport {
    final List<Long> customerRows = new ArrayList<>();

    @AfterEach void cleanPayments() {
        for (long id : customerRows) {
            jdbc.update("DELETE FROM payments WHERE customer_id=?", id);
            jdbc.update("DELETE FROM customers WHERE id=?", id);
        }
    }

    @Test void theChatAnswersAnUnregisteredQuestionWithTheLiveModel() throws Exception {
        long owner = person("OWNER");
        long first = branch(owner, "라이브가" + UUID.randomUUID().toString().substring(0, 4) + "점");
        long second = branch(owner, "라이브나" + UUID.randomUUID().toString().substring(0, 4) + "점");
        String no = "LIVE-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO customers(member_no,name) VALUES(?,'라이브 고객')", no);
        long customer = jdbc.queryForObject("SELECT id FROM customers WHERE member_no=?", Long.class, no);
        customerRows.add(customer);
        Instant now = Instant.now();
        pay(owner, customer, first, 30_000, "APPROVED", now);
        pay(owner, customer, first, 12_000, "APPROVED", now);
        pay(owner, customer, second, 50_000, "APPROVED", now);
        pay(owner, customer, second, 99_000, "FAILED", now);
        pay(owner, customer, second, 7_000, "APPROVED", now.minus(Duration.ofDays(100)));
        String question = System.getenv().getOrDefault("OPENSTUDY_AI_LIVE_QUESTION", "이번 달 지점별 결제 금액 합계 보여줘");

        var report = new StringBuilder("question=" + question + System.lineSeparator());
        try (var s = login(owner)) {
            long started = System.nanoTime();
            var response = ask(s, question, "all");
            report.append("askMs=").append((System.nanoTime() - started) / 1_000_000).append(System.lineSeparator())
                    .append("http=").append(response.statusCode()).append(System.lineSeparator());
            var body = body(response);
            for (String field : List.of("status", "errorCode", "resultCategory", "analysisId", "intentDomain", "message")) {
                report.append(field).append('=').append(body.get(field)).append(System.lineSeparator());
            }
            report.append("querySpec=").append(body.get("querySpec")).append(System.lineSeparator())
                    .append("viewSpec=").append(body.get("viewSpec")).append(System.lineSeparator())
                    .append("rows=").append(body.at("/envelope/datasets/0/rows")).append(System.lineSeparator())
                    .append("stages=").append(body.get("stages")).append(System.lineSeparator())
                    .append("securityGate=").append(body.get("securityGate")).append(System.lineSeparator())
                    .append("expected(this month)=").append(name(first)).append('=').append(approvedThisMonth(first)).append(", ")
                    .append(name(second)).append('=').append(approvedThisMonth(second)).append(System.lineSeparator());
            String executionId = body.get("executionId") == null ? null : body.get("executionId").asString(null);
            if (executionId != null && "COMPLETED".equals(body.get("status").asString())) {
                var rerun = send(s, "GET", "/api/ai/executions/" + executionId, null);
                report.append("rerunHttp=").append(rerun.statusCode()).append(System.lineSeparator())
                        .append("rerunRows=").append(body(rerun).at("/envelope/datasets/0/rows")).append(System.lineSeparator());
            }
            java.nio.file.Files.writeString(java.nio.file.Path.of("build/live-chat.txt"), report.toString());
            assertThat(response.statusCode()).as(report.toString()).isLessThan(500);
        }
    }

    void pay(long owner, long customer, long branch, long amount, String status, Instant at) {
        jdbc.update("INSERT INTO payments(customer_id,branch_id,purpose,method,amount_krw,status,idempotency_key,issued_pass_quantity,approved_at,failed_at,created_by) "
                        + "VALUES(?,?,'LOCKER','CASH',?,?,?,0,?,?,?)",
                customer, branch, amount, status, "LIVE-" + UUID.randomUUID(),
                "APPROVED".equals(status) ? Timestamp.from(at) : null, "FAILED".equals(status) ? Timestamp.from(at) : null, owner);
    }

    long approvedThisMonth(long branch) {
        var kst = java.time.ZoneId.of("Asia/Seoul");
        var month = java.time.YearMonth.now(kst);
        return count("SELECT COALESCE(SUM(amount_krw),0) FROM payments WHERE branch_id=? AND status='APPROVED' AND approved_at>=? AND approved_at<?",
                branch, Timestamp.from(month.atDay(1).atStartOfDay(kst).toInstant()), Timestamp.from(month.plusMonths(1).atDay(1).atStartOfDay(kst).toInstant()));
    }

    String name(long branch) {
        return jdbc.queryForObject("SELECT name FROM branches WHERE id=?", String.class, branch);
    }
}
