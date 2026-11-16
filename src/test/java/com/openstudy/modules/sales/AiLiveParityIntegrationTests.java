package com.openstudy.modules.sales;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #97: the chat answers representative sales and pass questions with the real model and engine, and every figure equals
 * the M9 sales API for the conditions the answer says it applied. Opt-in and spends tokens: OPENSTUDY_AI_LIVE_TESTS=true
 * with QWEN_API_KEY, QWEN_BASE_URL and QWEN_MODEL. OPENSTUDY_AI_PARITY_REPEATS (default 1) runs the question set that
 * many times; the stage latencies of every run feed the p50/p95 in build/ai-live-parity.md.
 *
 * <p>The fixture is the worked example of aggregation-api.md (October to December, refunds, a failed payment, room and
 * locker sales). The AI reference time is 2026-12-10 12:00 Asia/Seoul, so "지난달" is November.
 */
@MySqlIntegrationTest
@EnabledIfEnvironmentVariable(named = "OPENSTUDY_AI_LIVE_TESTS", matches = "true")
@TestPropertySource(properties = {"openstudy.ai.model-mode=live", "domainlens.model.mode=live",
        "openstudy.ai.fixed-as-of=2026-12-10T03:00:00Z"})
class AiLiveParityIntegrationTests extends SalesIntegrationSupport {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final LocalDate NOV_FROM = LocalDate.of(2026, 11, 1), NOV_TO = LocalDate.of(2026, 11, 30);

    /** One compared figure: what the chat showed and what the M9 API returns for the same conditions. */
    record Check(String label, Long ai, Long api) {
        boolean same() { return ai != null && ai.equals(api); }
    }

    /** What the answer says it applied: an inclusive Asia/Seoul period, or null when it states none. */
    record Applied(LocalDate from, LocalDate to) {
        String query() {
            return period(from + "T00:00", to.plusDays(1) + "T00:00");
        }
    }

    interface Comparison {
        List<Check> compare(Session s, JsonNode answer, Applied applied) throws Exception;
    }

    /** A question, the path it should take (an analysis ID or GENERATED) and the period it should apply. */
    record Case(String id, String question, String path, LocalDate from, LocalDate to, Comparison comparison) {}

    record Sample(int run, String caseId, String question, String path, String route, String status, String error,
            Applied applied, boolean periodAsExpected, List<Check> checks, long httpMs, Map<String, Long> stages,
            long tokens) {
        boolean passed() {
            return "COMPLETED".equals(status) && periodAsExpected && !checks.isEmpty() && checks.stream().allMatch(Check::same);
        }
    }

    final List<Sample> samples = new ArrayList<>();

    @AfterEach void cleanAi() {
        String actors = people.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        jdbc.update("DELETE FROM ai_messages WHERE conversation_id IN (SELECT id FROM ai_conversations WHERE employee_id IN (" + actors + "))");
        jdbc.update("DELETE FROM ai_executions WHERE employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM ai_conversations WHERE employee_id IN (" + actors + ")");
        jdbc.update("DELETE FROM ai_traces WHERE employee_id IN (" + actors + ")");
    }

    @Test void theLiveChatMatchesTheSalesApi() throws Exception {
        workedExample();
        String fifteen = jdbc.queryForObject("SELECT name FROM products WHERE name LIKE ? ORDER BY id LIMIT 1", String.class,
                token + " 1만5천");
        long fifteenId = jdbc.queryForObject("SELECT id FROM products WHERE name=?", Long.class, fifteen);
        String firstName = jdbc.queryForObject("SELECT name FROM branches WHERE id=?", String.class, first);
        LocalDate decFrom = LocalDate.of(2026, 12, 1);

        var cases = List.of(
                // Registered analyses: a keyword question, paraphrases the keyword rules do not catch (the model picks
                // the analysis), and other periods, a single branch and a single product.
                new Case("R1", "지점별 매출을 비교해줘", "openstudy.sales.branchComparison", decFrom, LocalDate.of(2026, 12, 10),
                        this::branchRevenue),
                new Case("R2", "지난달 지점별 매출을 비교해줘", "openstudy.sales.branchComparison", NOV_FROM, NOV_TO, this::branchRevenue),
                new Case("R3", "11월에 지점마다 순매출이 얼마였는지 정리해줘", "openstudy.sales.branchComparison", NOV_FROM, NOV_TO,
                        this::branchRevenue),
                new Case("R4", firstName + " 지난달 매출 보여줘", "openstudy.sales.branchComparison", NOV_FROM, NOV_TO,
                        (s, a, p) -> branchRevenueOf(s, a, p, first)),
                new Case("R5", "지난달 매출을 판매 항목별로 나눠서 보여줘", "openstudy.sales.byItemType", NOV_FROM, NOV_TO, this::purposeRevenue),
                new Case("R6", "지난달 가장 많이 팔린 이용권 상품 순위 알려줘", "openstudy.passes.topProducts", NOV_FROM, NOV_TO,
                        this::topProducts),
                new Case("R7", "지난달 '" + fifteen + "' 상품 판매량 알려줘", "openstudy.passes.topProducts", NOV_FROM, NOV_TO,
                        (s, a, p) -> productSales(s, a, p, fifteenId)),
                new Case("R8", "지난달 일별 매출 추이 보여줘", "openstudy.sales.dailyTrend", NOV_FROM, NOV_TO, this::dailyRevenue),
                // Generated path: general metrics no registered analysis answers.
                new Case("G1", "지난달 지점별 결제 금액 합계 보여줘", "GENERATED", NOV_FROM, NOV_TO,
                        (s, a, p) -> generatedByBranch(s, a, p, "approvedAmountKrw")),
                new Case("G2", "지난달 지점별 결제 건수 보여줘", "GENERATED", NOV_FROM, NOV_TO,
                        (s, a, p) -> generatedByBranch(s, a, p, "approvedPaymentCount")),
                new Case("G3", "이번 달 지점별 결제 금액 합계 보여줘", "GENERATED", decFrom, LocalDate.of(2026, 12, 31),
                        (s, a, p) -> generatedByBranch(s, a, p, "approvedAmountKrw")),
                new Case("G4", "지난달 이용권 발급 수 알려줘", "GENERATED", NOV_FROM, NOV_TO, this::generatedIssued));

        int repeats = Integer.parseInt(System.getenv().getOrDefault("OPENSTUDY_AI_PARITY_REPEATS", "1"));
        for (int run = 1; run <= repeats; run++) {
            for (var c : cases) samples.add(ask(run, c));
        }
        String report = report(repeats);
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/ai-live-parity.md"), report);
        assertThat(samples).as(report).allMatch(Sample::passed);
    }

    Sample ask(int run, Case c) throws Exception {
        try (var s = login(owner)) {
            var body = new HashMap<String, Object>();
            body.put("question", c.question());
            body.put("scope", "all");
            long started = System.nanoTime();
            var response = http(s, "POST", "/api/ai/queries", body, null);
            long httpMs = (System.nanoTime() - started) / 1_000_000;
            JsonNode a = node(response);
            String status = text(a, "status");
            String path = "GENERATED".equals(text(a, "semanticStatus")) ? "GENERATED" : text(a, "analysisId");
            JsonNode gate = a.path("securityGate");
            // live mode calls the registered-analysis picker even when the keyword rules decide, so this says only
            // whether the model was called, not which of the two chose the analysis.
            String route = path == null ? "-" : "GENERATED".equals(path) ? "생성 경로"
                    : gate.path("llmDurationMs").asLong(0) > 0 ? "등록 분석(모델 호출)" : "등록 분석(규칙만)";
            var stages = new LinkedHashMap<String, Long>();
            for (var stage : a.path("stages")) stages.merge(stage.get("stage").asString(), stage.get("durationMs").asLong(), Long::sum);
            long tokens = gate.path("llmTotalTokens").asLong(0) + gate.path("generationTotalTokens").asLong(0);
            Applied applied = applied(a);
            List<Check> checks = new ArrayList<>();
            boolean periodOk = applied != null && applied.from().equals(c.from()) && applied.to().equals(c.to());
            if ("COMPLETED".equals(status) && c.path().equals(path) && applied != null) {
                checks = c.comparison().compare(s, a, applied);
            }
            String error = text(a, "errorCode");
            if (!c.path().equals(path) && "COMPLETED".equals(status)) error = "경로가 " + path;
            return new Sample(run, c.id(), c.question(), c.path(), route, status == null ? "HTTP " + response.statusCode() : status,
                    error, applied, periodOk, checks, httpMs, stages, tokens);
        }
    }

    // ---- comparisons --------------------------------------------------------------------------------------------

    List<Check> branchRevenue(Session s, JsonNode a, Applied p) throws Exception {
        JsonNode rows = dataset(a, "branchSales").get("rows");
        JsonNode api = ok(s, "/api/sales/revenue/branches?" + p.query());
        var checks = new ArrayList<Check>();
        checks.add(new Check("지점 수", (long) rows.size(), (long) api.get("items").size()));
        for (var item : api.get("items")) branchChecks(checks, rows, item);
        return checks;
    }

    List<Check> branchRevenueOf(Session s, JsonNode a, Applied p, long branch) throws Exception {
        JsonNode rows = dataset(a, "branchSales").get("rows");
        JsonNode api = ok(s, "/api/sales/revenue/branches?branchIds=" + branch + "&" + p.query());
        var checks = new ArrayList<Check>();
        checks.add(new Check("지점 수", (long) rows.size(), 1L));
        for (var item : api.get("items")) branchChecks(checks, rows, item);
        return checks;
    }

    static void branchChecks(List<Check> checks, JsonNode rows, JsonNode item) {
        JsonNode row = rowOf(rows, "branch_id", item.get("branchId").asString());
        String name = item.get("branchName").asString();
        checks.add(new Check(name + " 승인액", value(row, "approved_krw"), item.get("approvedAmountKrw").asLong()));
        checks.add(new Check(name + " 환불액", value(row, "refund_krw"), item.get("refundAmountKrw").asLong()));
        checks.add(new Check(name + " 순매출", value(row, "net_krw"), item.get("netAmountKrw").asLong()));
        checks.add(new Check(name + " 승인 건수", value(row, "approved_count"), item.get("approvedPaymentCount").asLong()));
        checks.add(new Check(name + " 환불 건수", value(row, "refund_count"), item.get("refundCount").asLong()));
    }

    List<Check> purposeRevenue(Session s, JsonNode a, Applied p) throws Exception {
        JsonNode rows = dataset(a, "purposeSales").get("rows");
        JsonNode api = ok(s, "/api/sales/revenue?" + p.query());
        Map<String, String> codes = Map.of("이용권", "PASS", "스터디룸", "ROOM", "사물함", "LOCKER");
        var checks = new ArrayList<Check>();
        for (var purpose : api.get("byPurpose")) {
            String label = purpose.get("purpose").asString();
            JsonNode row = rowOf(rows, "purpose", codes.getOrDefault(label, label));
            checks.add(new Check(label + " 승인액", value(row, "approved_krw"), purpose.get("approvedAmountKrw").asLong()));
            checks.add(new Check(label + " 환불액", value(row, "refund_krw"), purpose.get("refundAmountKrw").asLong()));
            checks.add(new Check(label + " 순매출", value(row, "net_krw"), purpose.get("netAmountKrw").asLong()));
        }
        return checks;
    }

    List<Check> topProducts(Session s, JsonNode a, Applied p) throws Exception {
        JsonNode rows = dataset(a, "topProducts").get("rows");
        JsonNode api = ok(s, "/api/sales/passes/products?" + p.query() + "&page=1&pageSize=" + Math.max(1, rows.size()));
        var checks = new ArrayList<Check>();
        checks.add(new Check("상품 수", (long) rows.size(), (long) api.get("items").size()));
        for (int i = 0; i < api.get("items").size(); i++) {
            JsonNode item = api.get("items").get(i);
            JsonNode row = i < rows.size() ? rows.get(i) : null;
            String rank = (i + 1) + "위";
            checks.add(new Check(rank + " 상품 ID", row == null ? null : Long.parseLong(row.get("product_id").asString()),
                    Long.parseLong(item.get("productId").asString())));
            checks.add(new Check(rank + " 순판매", value(row, "net_units"), item.get("netSoldQuantity").asLong()));
            checks.add(new Check(rank + " 판매", value(row, "issued_units"), item.get("soldQuantity").asLong()));
            checks.add(new Check(rank + " 환불", value(row, "refunded_units"), item.get("refundedQuantity").asLong()));
        }
        return checks;
    }

    List<Check> productSales(Session s, JsonNode a, Applied p, long product) throws Exception {
        JsonNode rows = dataset(a, "topProducts").get("rows");
        JsonNode api = ok(s, "/api/sales/passes/products?productId=" + product + "&" + p.query() + "&page=1&pageSize=20");
        var checks = new ArrayList<Check>();
        // The answer is about one product: it shows only that product, with the API's figures for it.
        checks.add(new Check("상품 수", (long) rows.size(), (long) api.get("items").size()));
        for (var item : api.get("items")) {
            JsonNode row = rowOf(rows, "product_id", item.get("productId").asString());
            checks.add(new Check("순판매", value(row, "net_units"), item.get("netSoldQuantity").asLong()));
            checks.add(new Check("판매", value(row, "issued_units"), item.get("soldQuantity").asLong()));
            checks.add(new Check("환불", value(row, "refunded_units"), item.get("refundedQuantity").asLong()));
        }
        return checks;
    }

    List<Check> dailyRevenue(Session s, JsonNode a, Applied p) throws Exception {
        JsonNode rows = dataset(a, "dailySales").get("rows");
        JsonNode api = ok(s, "/api/sales/trend?granularity=day&" + p.query());
        var checks = new ArrayList<Check>();
        long aiNet = 0, apiNet = 0;
        for (var item : api.get("items")) {
            String day = OffsetDateTime.parse(item.get("bucketStart").asString()).atZoneSameInstant(KST).toLocalDate().toString();
            JsonNode row = rowOf(rows, "date_kst", day);
            long net = item.at("/revenue/netAmountKrw").asLong();
            apiNet += net;
            aiNet += row == null ? 0 : row.get("net_krw").asLong();
            // Days without sales may be left out of the chart; a day with sales must be there with the same figures.
            if (net != 0 || item.at("/revenue/approvedPaymentCount").asLong() != 0 || row != null) {
                checks.add(new Check(day + " 순매출", value(row, "net_krw"), net));
                checks.add(new Check(day + " 승인액", value(row, "approved_krw"), item.at("/revenue/approvedAmountKrw").asLong()));
            }
        }
        checks.add(new Check("기간 순매출 합계", aiNet, apiNet));
        return checks;
    }

    /** A generated table of branch name (d0) and one metric (m0) against the same metric of the branch comparison API. */
    List<Check> generatedByBranch(Session s, JsonNode a, Applied p, String apiField) throws Exception {
        JsonNode rows = a.at("/envelope/datasets/0/rows");
        JsonNode api = ok(s, "/api/sales/revenue/branches?" + p.query());
        var checks = new ArrayList<Check>();
        for (var item : api.get("items")) {
            String name = item.get("branchName").asString();
            JsonNode row = rowOf(rows, "d0", name);
            long expected = item.get(apiField).asLong();
            // A branch without a single approved payment has no group in the generated query: shown as no row, read as 0.
            checks.add(new Check(name, row == null ? (expected == 0 ? 0L : null) : row.get("m0").asLong(), expected));
        }
        return checks;
    }

    List<Check> generatedIssued(Session s, JsonNode a, Applied p) throws Exception {
        JsonNode rows = a.at("/envelope/datasets/0/rows");
        JsonNode api = ok(s, "/api/sales/passes/volume?" + p.query());
        Long ai = rows.size() == 1 ? rows.get(0).get("m0").asLong() : null;
        return List.of(new Check("이용권 발급 수", ai, api.at("/totals/issuedCount").asLong()));
    }

    // ---- answer reading -----------------------------------------------------------------------------------------

    /** The period the answer says it applied: its conditions, or the [from, to) leaves of a generated query. */
    static Applied applied(JsonNode a) {
        JsonNode period = a.at("/conditions/period");
        if (period.isObject() && period.has("from")) {
            return new Applied(LocalDate.parse(period.get("from").asString()), LocalDate.parse(period.get("to").asString()));
        }
        var leaves = new ArrayList<JsonNode>();
        leaves(a.get("querySpec"), leaves);
        OffsetDateTime from = null, to = null;
        for (var leaf : leaves) {
            String op = leaf.get("operator").asString();
            JsonNode values = leaf.get("values");
            if (values == null || values.isEmpty()) continue;
            try {
                var at = OffsetDateTime.parse(values.get(0).asString());
                if ("GTE".equals(op)) from = at;
                if ("LT".equals(op)) to = at;
            } catch (java.time.DateTimeException notAnInstant) {
                // not a period leaf
            }
        }
        if (from == null || to == null) return null;
        return new Applied(from.atZoneSameInstant(KST).toLocalDate(), to.atZoneSameInstant(KST).minusNanos(1).toLocalDate());
    }

    static void leaves(JsonNode node, List<JsonNode> out) {
        if (node == null) return;
        if (node.isObject() && node.has("operator") && node.has("field")) out.add(node);
        for (var child : node) leaves(child, out);
    }

    static JsonNode dataset(JsonNode answer, String id) {
        for (var dataset : answer.at("/envelope/datasets")) if (id.equals(dataset.get("id").asString())) return dataset;
        throw new AssertionError("no dataset " + id + " in " + answer.get("envelope"));
    }

    static JsonNode rowOf(JsonNode rows, String key, String value) {
        for (var row : rows) if (row.has(key) && row.get(key).asString().equals(value)) return row;
        return null;
    }

    static Long value(JsonNode row, String key) {
        return row == null || !row.has(key) || row.get(key).isNull() ? null : row.get(key).asLong();
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    // ---- report -------------------------------------------------------------------------------------------------

    String report(int repeats) {
        var out = new StringBuilder("# #97 실제 모델 수치 대조\n\n")
                .append("- 모델: ").append(System.getenv().getOrDefault("QWEN_MODEL", "?"))
                .append(" · 반복: ").append(repeats).append("회 · 질문 ").append(samples.size()).append("건\n")
                .append("- 통과: ").append(samples.stream().filter(Sample::passed).count()).append("건\n\n")
                .append("| 회차 | 사례 | 질문 | 기대 경로 | 경로 | 상태 | 적용 기간 | 기간 일치 | 대조 | 불일치 | HTTP ms | 토큰 |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (var sample : samples) {
            var wrong = sample.checks().stream().filter(c -> !c.same())
                    .map(c -> c.label() + " " + c.ai() + "≠" + c.api()).toList();
            out.append("| ").append(sample.run()).append(" | ").append(sample.caseId()).append(" | ").append(sample.question())
                    .append(" | ").append(sample.path().replace("openstudy.", "")).append(" | ").append(sample.route())
                    .append(" | ").append(sample.status()).append(sample.error() == null ? "" : " " + sample.error())
                    .append(" | ").append(sample.applied() == null ? "-" : sample.applied().from() + "~" + sample.applied().to())
                    .append(" | ").append(sample.periodAsExpected() ? "예" : "아니오")
                    .append(" | ").append(sample.checks().size())
                    .append(" | ").append(wrong.isEmpty() ? "-" : String.join("; ", wrong))
                    .append(" | ").append(sample.httpMs()).append(" | ").append(sample.tokens()).append(" |\n");
        }
        out.append("\n## 단계별 지연 (ms)\n\n| 경로 | 단계 | n | p50 | p95 | 최대 |\n|---|---|---|---|---|---|\n");
        var groups = new TreeMap<String, Map<String, List<Long>>>();
        for (var sample : samples) {
            if (!"COMPLETED".equals(sample.status())) continue;
            String group = "GENERATED".equals(sample.path()) ? "생성 경로" : sample.route();
            var stages = groups.computeIfAbsent(group, k -> new LinkedHashMap<>());
            sample.stages().forEach((stage, ms) -> stages.computeIfAbsent(stage, k -> new ArrayList<>()).add(ms));
            stages.computeIfAbsent("HTTP 전체", k -> new ArrayList<>()).add(sample.httpMs());
        }
        groups.forEach((group, stages) -> stages.forEach((stage, values) -> out.append("| ").append(group).append(" | ")
                .append(stage).append(" | ").append(values.size()).append(" | ").append(percentile(values, 50)).append(" | ")
                .append(percentile(values, 95)).append(" | ").append(values.stream().mapToLong(Long::longValue).max().orElse(0))
                .append(" |\n")));
        return out.toString();
    }

    /** Nearest-rank percentile. */
    static long percentile(List<Long> values, int p) {
        var sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }
}
