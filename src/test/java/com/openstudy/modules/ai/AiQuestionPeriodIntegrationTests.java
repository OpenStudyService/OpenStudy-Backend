package com.openstudy.modules.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.openstudy.modules.ai.service.OpenStudyAnalyses;
import com.openstudy.support.database.MySqlIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 2026-11-13: a registered analysis runs over the period its question names. "이번 달 지점별 매출" and "지난 달 지점별
 * 매출" both choose the branch sales comparison (POC-001), but no longer return the same result: the second runs over
 * last month, says so in its title and conditions, and keeps that period when it runs again.
 */
@MySqlIntegrationTest
class AiQuestionPeriodIntegrationTests extends AiTestSupport {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    final List<Long> customerRows = new ArrayList<>();
    long owner, first, second;
    YearMonth thisMonth, lastMonth;

    @BeforeEach void sales() {
        owner = person("OWNER");
        first = branch(owner, "기간가" + UUID.randomUUID().toString().substring(0, 4) + "점");
        second = branch(owner, "기간나" + UUID.randomUUID().toString().substring(0, 4) + "점");
        thisMonth = YearMonth.now(KST);
        lastMonth = thisMonth.minusMonths(1);
        long customer = customer();
        Instant now = Instant.now();
        Instant lastMonthMiddle = lastMonth.atDay(15).atTime(12, 0).atZone(KST).toInstant();
        pay(customer, first, 30_000, now);
        pay(customer, second, 50_000, now);
        pay(customer, first, 11_000, lastMonthMiddle);
        pay(customer, second, 22_000, lastMonthMiddle);
        pay(customer, second, 3_000, lastMonthMiddle);
    }

    @AfterEach void cleanSales() {
        for (long id : customerRows) {
            jdbc.update("DELETE FROM payments WHERE customer_id=?", id);
            jdbc.update("DELETE FROM customers WHERE id=?", id);
        }
    }

    @Test void thisMonthAndLastMonthAreDifferentResults() throws Exception {
        try (var s = login(owner)) {
            JsonNode thisMonthAnswer = body(ask(s, "이번 달 지점별 매출을 비교해줘", "all"));
            JsonNode lastMonthAnswer = body(ask(s, "지난 달 지점별 매출을 비교해줘", "all"));

            assertThat(thisMonthAnswer.get("analysisId").asString()).isEqualTo(OpenStudyAnalyses.ID_POC_001);
            assertThat(lastMonthAnswer.get("analysisId").asString()).isEqualTo(OpenStudyAnalyses.ID_POC_001);
            assertThat(net(thisMonthAnswer)).containsEntry(name(first), 30_000L).containsEntry(name(second), 50_000L);
            assertThat(net(lastMonthAnswer)).containsEntry(name(first), 11_000L).containsEntry(name(second), 25_000L);

            // "이번 달" is the analysis default: no changed period.
            assertThat(thisMonthAnswer.at("/conditions/periodSource").asString()).isEqualTo("DEFAULT");
            assertThat(lastMonthAnswer.at("/conditions/period/from").asString()).isEqualTo(lastMonth.atDay(1).toString());
            assertThat(lastMonthAnswer.at("/conditions/period/to").asString()).isEqualTo(lastMonth.atEndOfMonth().toString());
            String label = lastMonth.getYear() + "년 " + lastMonth.getMonthValue() + "월";
            assertThat(titles(lastMonthAnswer)).anyMatch(title -> title.startsWith(label)).noneMatch(title -> title.startsWith("이번 달"));
            assertThat(titles(thisMonthAnswer)).anyMatch(title -> title.startsWith("이번 달"));
        }
    }

    @Test void aRunAgainKeepsTheAskedPeriod() throws Exception {
        try (var s = login(owner)) {
            String executionId = body(ask(s, "지난 달 지점별 매출을 비교해줘", "all")).get("executionId").asString();
            JsonNode again = body(send(s, "GET", "/api/ai/executions/" + executionId, null));
            assertThat(again.at("/conditions/period/from").asString()).isEqualTo(lastMonth.atDay(1).toString());
            Map<String, Long> net = new HashMap<>();
            for (var row : again.at("/envelope/datasets").get(datasetIndex(again, "branchSales")).get("rows")) {
                net.put(row.get("branch_name").asString(), row.get("net_krw").asLong());
            }
            assertThat(net).containsEntry(name(first), 11_000L).containsEntry(name(second), 25_000L);
        }
    }

    @Test void twoPeriodsAskWhichOne() throws Exception {
        try (var s = login(owner)) {
            JsonNode answer = body(ask(s, "이번 달과 지난달 지점별 매출을 비교해줘", "all"));
            assertThat(answer.get("status").asString()).isEqualTo("CLARIFICATION");
            assertThat(answer.get("errorCode").asString()).isEqualTo("PERIOD_AMBIGUOUS");
            assertThat(answer.get("envelope").isNull()).isTrue();
        }
    }

    @Test void aPeriodBeyondTheAnalysisLimitAsksForAShorterOne() throws Exception {
        try (var s = login(owner)) {
            JsonNode answer = body(ask(s, "올해 직원별 근무시간을 비교해줘", "all"));
            assertThat(answer.get("analysisId").asString()).isEqualTo(OpenStudyAnalyses.ID_POC_010);
            assertThat(answer.get("status").asString()).isEqualTo("CLARIFICATION");
            assertThat(answer.get("errorCode").asString()).isEqualTo("PERIOD_TOO_LONG");
        }
    }

    // --- fixtures ---

    long customer() {
        String no = "PER-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO customers(member_no,name) VALUES(?,'기간 고객')", no);
        long id = jdbc.queryForObject("SELECT id FROM customers WHERE member_no=?", Long.class, no);
        customerRows.add(id);
        return id;
    }

    void pay(long customer, long branch, long amount, Instant approvedAt) {
        jdbc.update("INSERT INTO payments(customer_id,branch_id,purpose,method,amount_krw,status,idempotency_key,issued_pass_quantity,approved_at,created_by) "
                        + "VALUES(?,?,'LOCKER','CASH',?,'APPROVED',?,0,?,?)",
                customer, branch, amount, "PER-" + UUID.randomUUID(), Timestamp.from(approvedAt), owner);
    }

    String name(long branch) {
        return jdbc.queryForObject("SELECT name FROM branches WHERE id=?", String.class, branch);
    }

    static int datasetIndex(JsonNode answer, String id) {
        var datasets = answer.at("/envelope/datasets");
        for (int i = 0; i < datasets.size(); i++) if (id.equals(datasets.get(i).get("id").asString())) return i;
        throw new AssertionError("no dataset " + id + " in " + answer);
    }

    static Map<String, Long> net(JsonNode answer) {
        Map<String, Long> net = new HashMap<>();
        for (var row : answer.at("/envelope/datasets").get(datasetIndex(answer, "branchSales")).get("rows")) {
            net.put(row.get("branch_name").asString(), row.get("net_krw").asLong());
        }
        return net;
    }

    static List<String> titles(JsonNode answer) {
        var titles = new ArrayList<String>();
        for (var component : answer.at("/viewSpec/components")) {
            var title = component.at("/options/title");
            if (title.isString()) titles.add(title.asString());
        }
        return titles;
    }

}
