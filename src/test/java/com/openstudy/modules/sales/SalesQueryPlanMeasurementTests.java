package com.openstudy.modules.sales;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.LongStream;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.openstudy.modules.sales.dto.request.RefundBasis;
import com.openstudy.modules.sales.repository.SalesSourceRepository;
import com.openstudy.modules.sales.repository.SalesSourceRepository.SourceFilter;
import com.openstudy.support.database.MySqlIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #89 (NFR-PERF-04): the execution plans and times of the representative M9 aggregations at a representative data
 * volume. It measures, it does not assert a time, so it only runs when asked:
 * {@code OPENSTUDY_QUERY_PLAN=true ./gradlew test --tests '*SalesQueryPlanMeasurementTests'}. The report goes to
 * {@code build/query-plans/<OPENSTUDY_QUERY_PLAN_LABEL>.md}.
 * <p>The SQL is the repository's own: a statement inspector records what Hibernate sends, and EXPLAIN ANALYZE runs that
 * text with the same binds. The seed rows are written server side with foreign key checks off on one connection, so their
 * customer, product, revision and employee IDs need no parent rows; the branch IDs 900001~900010 are reserved for this
 * test and every seeded row is removed afterwards.</p>
 */
@MySqlIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "OPENSTUDY_QUERY_PLAN", matches = "true")
@TestPropertySource(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "com.openstudy.modules.sales.SalesQueryPlanMeasurementTests$Capture")
class SalesQueryPlanMeasurementTests {
    /** Branches of the representative business: 10, of which a manager typically reads 3 and an employee 1. */
    static final long FIRST_BRANCH = 900001;
    static final int BRANCH_COUNT = 10;
    /** Two years of history (2025-01-01 ~ 2026-12-31 UTC), so a one-year window still leaves half the table outside. */
    static final int DAYS = 730;
    static final int PER_DAY = Integer.parseInt(System.getenv().getOrDefault("OPENSTUDY_QUERY_PLAN_PER_DAY", "12"));
    static final int WARMUPS = 2, RUNS = 5;
    /** October 2026 in Asia/Seoul. */
    static final Instant MONTH_FROM = Instant.parse("2026-09-30T15:00:00Z"), MONTH_TO = Instant.parse("2026-10-31T15:00:00Z");
    /** The 365 Asia/Seoul days ending with October 2026: the longest window the 366-day limit allows, give or take one. */
    static final Instant YEAR_FROM = Instant.parse("2025-10-31T15:00:00Z");
    static final DateTimeFormatter UTC_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    @Autowired JdbcTemplate jdbc;
    @Autowired SalesSourceRepository sources;
    @Autowired TransactionTemplate transactions;
    final StringBuilder report = new StringBuilder();

    /** Records every SQL text Hibernate prepares. Instantiated by Hibernate by name, so the list is static. */
    public static class Capture implements StatementInspector {
        static final List<String> SQL = new CopyOnWriteArrayList<>();
        @Override public String inspect(String sql) { SQL.add(sql); return sql; }
    }

    @BeforeAll void seed() {
        removeSeed();
        long payments = (long) DAYS * PER_DAY * BRANCH_COUNT;
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET SESSION foreign_key_checks = 0");
                statement.execute("SET SESSION cte_max_recursion_depth = " + (payments + 1));
                // 70% PASS, 20% ROOM, 10% LOCKER; 3% FAILED, 5% REFUNDED later, the rest APPROVED. CRC32 keeps the
                // purpose, the status, the branch and the time of day independent of each other.
                statement.executeUpdate("INSERT INTO payments (customer_id, branch_id, product_id, product_name_snapshot,"
                        + " purpose, method, amount_krw, status, idempotency_key, issued_pass_quantity, approved_at, failed_at,"
                        + " created_by, created_at)"
                        + " WITH RECURSIVE seq (n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM seq WHERE n + 1 < " + payments + ")"
                        + " SELECT 920000 + s.n % 3000, s.branch, IF(s.purpose = 'PASS', 910001 + s.n % 8, NULL),"
                        + " IF(s.purpose = 'PASS', CONCAT('측정 상품 ', s.n % 8), NULL), s.purpose, 'CARD',"
                        + " 10000 + (s.n % 20) * 5000, s.status, CONCAT('perf-', s.n),"
                        + " IF(s.purpose = 'PASS' AND s.status <> 'FAILED', 1, 0), IF(s.status = 'FAILED', NULL, s.at),"
                        + " IF(s.status = 'FAILED', s.at, NULL), 930001, s.at"
                        + " FROM (SELECT n, " + FIRST_BRANCH + " + n % " + BRANCH_COUNT + " AS branch,"
                        + " ELT(1 + CRC32(CONCAT('p', n)) % 10, 'PASS', 'PASS', 'PASS', 'PASS', 'PASS', 'PASS', 'PASS',"
                        + " 'ROOM', 'ROOM', 'LOCKER') AS purpose,"
                        + " CASE WHEN CRC32(CONCAT('s', n)) % 100 < 3 THEN 'FAILED'"
                        + " WHEN CRC32(CONCAT('s', n)) % 100 < 8 THEN 'REFUNDED' ELSE 'APPROVED' END AS status,"
                        + " TIMESTAMP('2025-01-01 00:00:00') + INTERVAL (n DIV " + (PER_DAY * BRANCH_COUNT) + ") DAY"
                        + " + INTERVAL (CRC32(CONCAT('t', n)) % 86400) SECOND AS at FROM seq) s");
                // Every approved PASS payment has its pass (PaymentService issues it in the same transaction).
                statement.executeUpdate("INSERT INTO passes (customer_id, product_id, purchase_payment_id, purchase_branch_id,"
                        + " purchase_product_revision_id, kind, snapshot_price_krw, snapshot_product_name, snapshot_category_code,"
                        + " snapshot_category_name, snapshot_minutes, remaining_minutes, status, started_at, created_at)"
                        + " SELECT p.customer_id, p.product_id, p.id, p.branch_id, 940000 + p.product_id % 100, 'TIME',"
                        + " p.amount_krw, p.product_name_snapshot, CONCAT('PERF', p.product_id % 3),"
                        + " CONCAT('측정 분류 ', p.product_id % 3), 600, 600, IF(p.status = 'REFUNDED', 'REFUNDED', 'ACTIVE'),"
                        + " p.approved_at, p.approved_at FROM payments p WHERE " + seedBranches("p.branch_id")
                        + " AND p.purpose = 'PASS' AND p.approved_at IS NOT NULL");
                // A refund lands 1~40 days after its payment, so the two refund bases put many of them in different months.
                statement.executeUpdate("INSERT INTO refunds (payment_id, processing_branch_id, processed_by, amount_krw, reason,"
                        + " idempotency_key, refunded_at) SELECT p.id, p.branch_id, 930001, p.amount_krw, '측정',"
                        + " CONCAT('perf-r-', p.id), p.approved_at + INTERVAL (1 + p.id % 40) DAY FROM payments p WHERE "
                        + seedBranches("p.branch_id") + " AND p.status = 'REFUNDED'");
                statement.execute("SET SESSION foreign_key_checks = 1");
                statement.execute("ANALYZE TABLE payments, refunds, passes");
            }
            return null;
        });
        report.append("# M9 집계 실행 계획 측정: ").append(System.getenv().getOrDefault("OPENSTUDY_QUERY_PLAN_LABEL", "run"))
                .append("\n\n- 측정 시각(UTC): ").append(Instant.now()).append("\n- MySQL: ")
                .append(jdbc.queryForObject("SELECT VERSION()", String.class)).append("\n- 시드: 지점 ").append(BRANCH_COUNT)
                .append("개 × ").append(DAYS).append("일 × 지점당 하루 ").append(PER_DAY).append("건\n- 행 수: ")
                .append(count("payments")).append(" payments, ").append(count("refunds")).append(" refunds, ")
                .append(count("passes")).append(" passes (시드 외 행 포함 전체)\n- 시간: 저장소 메서드 호출의 중앙값(")
                .append(WARMUPS).append("회 예열 뒤 ").append(RUNS).append("회), EXPLAIN ANALYZE는 그 뒤 별도 실행\n\n## 인덱스\n\n");
        for (String table : List.of("payments", "refunds", "passes")) {
            report.append("- `").append(table).append("`: ");
            var keys = jdbc.queryForList("SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols"
                    + " FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"
                    + " GROUP BY INDEX_NAME ORDER BY INDEX_NAME", table);
            report.append(String.join(", ", keys.stream().map(row -> row.get("INDEX_NAME") + "(" + row.get("cols") + ")")
                    .toList())).append('\n');
        }
    }

    @AfterAll void writeReport() throws IOException {
        removeSeed();
        Path out = Path.of(System.getenv().getOrDefault("OPENSTUDY_QUERY_PLAN_OUT", "build/query-plans"));
        Files.createDirectories(out);
        Files.writeString(out.resolve(System.getenv().getOrDefault("OPENSTUDY_QUERY_PLAN_LABEL", "run") + ".md"), report,
                StandardCharsets.UTF_8);
    }

    @Test void measure() {
        var one = branches(1); var three = branches(3); var all = branches(BRANCH_COUNT);
        measure("매출 · 처리일 기준 · 1지점 · 1개월", () -> sources.totals(filter(one, MONTH_FROM, RefundBasis.PROCESSED_DATE, null)));
        measure("매출 · 처리일 기준 · 3지점 · 1개월", () -> sources.totals(filter(three, MONTH_FROM, RefundBasis.PROCESSED_DATE, null)));
        measure("매출 · 원결제일 기준 · 3지점 · 1개월", () -> sources.totals(filter(three, MONTH_FROM, RefundBasis.PAYMENT_DATE, null)));
        measure("지점 비교 · 처리일 기준 · 10지점 · 1년", () -> sources.totals(filter(all, YEAR_FROM, RefundBasis.PROCESSED_DATE, null)));
        measure("지점 비교 · 원결제일 기준 · 10지점 · 1년", () -> sources.totals(filter(all, YEAR_FROM, RefundBasis.PAYMENT_DATE, null)));
        measure("이용권 판매량·상품 순위 · 3지점 · 1개월", () -> sources.passTotals(filter(three, MONTH_FROM, RefundBasis.PROCESSED_DATE, null), false));
        measure("이용권 판매량·상품 순위 · 분류 필터 · 10지점 · 1년", () -> sources.passTotals(
                filter(all, YEAR_FROM, RefundBasis.PROCESSED_DATE, "PERF1"), false));
        measure("추세 · 매출 · 일 단위 · 10지점 · 1년", () -> sources.dayTotals(filter(all, YEAR_FROM, RefundBasis.PROCESSED_DATE, null)));
        measure("추세 · 이용권 수량 · 일 단위 · 10지점 · 1년", () -> sources.passTotals(
                filter(all, YEAR_FROM, RefundBasis.PROCESSED_DATE, null), true));
        measure("최소 원장 · 3지점 · 1개월 · 첫 페이지(20)", () -> ledger(
                filter(three, MONTH_FROM, RefundBasis.PROCESSED_DATE, null), 20));
        measure("최소 원장 · 10지점 · 1년 · 첫 페이지(100)", () -> ledger(
                filter(all, YEAR_FROM, RefundBasis.PROCESSED_DATE, null), 100));
        measure("최소 원장 · 3지점 · 1개월 · 원결제일 기준 · 첫 페이지(20)", () -> ledger(
                filter(three, MONTH_FROM, RefundBasis.PAYMENT_DATE, null), 20));
        assertThat(report).contains("EXPLAIN");
    }

    private void measure(String label, Supplier<List<?>> call) {
        for (int index = 0; index < WARMUPS; index++) transactions.execute(status -> call.get());
        long[] times = new long[RUNS];
        List<String> sqls = List.of();
        int resultSize = 0;
        for (int index = 0; index < RUNS; index++) {
            Capture.SQL.clear();
            long start = System.nanoTime();
            resultSize = transactions.execute(status -> call.get()).size();
            times[index] = System.nanoTime() - start;
            sqls = List.copyOf(Capture.SQL);
        }
        Arrays.sort(times);
        report.append("\n## ").append(label).append("\n\n- 호출 시간 중앙값: ")
                .append(String.format("%.1f ms", times[RUNS / 2] / 1e6)).append(" (최소 ")
                .append(String.format("%.1f", times[0] / 1e6)).append(", 최대 ").append(String.format("%.1f", times[RUNS - 1] / 1e6))
                .append(")\n- 결과 행: ").append(resultSize).append("\n- SQL ").append(sqls.size()).append("개\n");
        for (String sql : sqls) {
            report.append("\n```sql\n").append(sql).append("\n```\n\n```text\n").append(explain(sql, label)).append("```\n");
        }
    }

    /** The binds of the filter being measured, as the repository appends them, and the ledger page size (0: none). */
    private List<Object> currentBinds;
    private int currentLimit;

    /** Runs EXPLAIN ANALYZE on the recorded SQL: each WHERE fragment binds the filter once, a ledger page then LIMIT/OFFSET. */
    private String explain(String sql, String label) {
        long placeholders = sql.chars().filter(character -> character == '?').count();
        var values = new ArrayList<Object>();
        long fragments = (placeholders - (currentLimit > 0 ? 2 : 0)) / currentBinds.size();
        for (long index = 0; index < fragments; index++) values.addAll(currentBinds);
        if (currentLimit > 0) {
            values.add(currentLimit);
            values.add(0);
        }
        assertThat(values).as(label).hasSize((int) placeholders);
        return jdbc.execute((ConnectionCallback<String>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement("EXPLAIN ANALYZE " + sql)) {
                for (int index = 0; index < values.size(); index++) statement.setObject(index + 1, values.get(index));
                try (var result = statement.executeQuery()) {
                    var text = new StringBuilder();
                    while (result.next()) text.append(result.getString(1)).append('\n');
                    return text.toString();
                }
            }
        });
    }

    private SourceFilter filter(List<Long> branchIds, Instant from, RefundBasis basis, String categoryCode) {
        var filter = new SourceFilter(branchIds, from, MONTH_TO, basis, null, null, categoryCode);
        currentBinds = binds(filter);
        currentLimit = 0;
        return filter;
    }

    /** branchIds, from, to, then the pass filter (categoryCode), as SalesSourceRepository appends them. */
    private static List<Object> binds(SourceFilter filter) {
        var binds = new ArrayList<Object>(filter.branchIds());
        binds.add(UTC_TEXT.format(LocalDateTime.ofInstant(filter.from(), ZoneOffset.UTC)));
        binds.add(UTC_TEXT.format(LocalDateTime.ofInstant(filter.to(), ZoneOffset.UTC)));
        if (filter.categoryCode() != null) binds.add(filter.categoryCode());
        return binds;
    }

    /** A first ledger page of both kinds, newest first: the default of GET /sales/ledger. */
    private List<?> ledger(SourceFilter filter, int pageSize) {
        currentLimit = pageSize;
        return sources.ledger(filter, null, true, 0, pageSize);
    }

    private static List<Long> branches(int count) {
        return LongStream.range(FIRST_BRANCH, FIRST_BRANCH + count).boxed().toList();
    }

    private static String seedBranches(String column) {
        return column + " BETWEEN " + FIRST_BRANCH + " AND " + (FIRST_BRANCH + BRANCH_COUNT - 1);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void removeSeed() {
        jdbc.update("DELETE x FROM passes x WHERE " + seedBranches("x.purchase_branch_id"));
        jdbc.update("DELETE r FROM refunds r JOIN payments p ON p.id = r.payment_id WHERE " + seedBranches("p.branch_id"));
        jdbc.update("DELETE FROM payments WHERE " + seedBranches("branch_id"));
    }
}
