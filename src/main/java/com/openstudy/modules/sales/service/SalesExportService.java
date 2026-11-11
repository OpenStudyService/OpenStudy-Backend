package com.openstudy.modules.sales.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.openstudy.common.audit.AuditEvent;
import com.openstudy.common.audit.AuditTrail;
import com.openstudy.common.exception.ApiException;
import com.openstudy.common.export.CsvFile;
import com.openstudy.common.web.ListQuery;
import com.openstudy.modules.branch.service.SessionBranchService;
import com.openstudy.modules.sales.dto.request.AggregationCondition;
import com.openstudy.modules.sales.dto.request.BranchRevenueSort;
import com.openstudy.modules.sales.dto.request.LedgerEntryType;
import com.openstudy.modules.sales.dto.request.TrendMeasure;
import com.openstudy.modules.sales.dto.response.AggregationMeta;
import com.openstudy.modules.sales.dto.response.PassVolume;
import com.openstudy.modules.sales.dto.response.RevenueAmounts;

/**
 * #127 GET /sales/exports/{report} (analysis-reuse-api.md "CSV 내보내기"): the same services, conditions, permissions and
 * errors as the screen API of each report, written as a CSV table. Pages are read until the 1,000-row cap; every export
 * is audited with its conditions and row count, never with the data.
 */
@Service
@RequiredArgsConstructor
public class SalesExportService {
    /** report → title, in the order of the contract table. */
    public static final Map<String, String> REPORTS = new LinkedHashMap<>();
    static {
        REPORTS.put("revenue", "매출 요약");
        REPORTS.put("revenue-branches", "지점별 매출 비교");
        REPORTS.put("ledger", "매출 원장");
        REPORTS.put("trend", "매출 추이");
        REPORTS.put("passes-volume", "이용권 판매량");
        REPORTS.put("passes-products", "이용권 상품별 판매");
        REPORTS.put("passes-usage", "이용권 사용 현황");
    }
    private static final int PAGE = 100;
    private static final List<CsvFile.Column> AMOUNTS = List.of(num("승인 금액(원)"), num("환불 금액(원)"), num("순매출(원)"),
            num("승인 결제 수(건)"), num("환불 수(건)"), text("데이터 상태"));
    private static final List<CsvFile.Column> VOLUME = List.of(num("결제 수(건)"), num("판매 수량(개)"), num("환불 수량(개)"),
            num("순판매 수량(개)"), num("발급 수(개)"), text("데이터 상태"));

    private final SalesRevenueService revenue;
    private final PassSalesService passes;
    private final SalesTrendService trends;
    private final PassUsageService usage;
    private final SessionBranchService branches;
    private final AuditTrail audit;
    private final ObjectMapper json;

    /** The screen-only options of a report: the branch order, the ledger entry type, the trend measure. */
    public record Options(BranchRevenueSort sort, ListQuery.Direction direction, LedgerEntryType entryType, TrendMeasure measure) {}

    public record Export(String name, CsvFile.Table table) {}

    @Transactional
    public Export export(long requester, String report, AggregationCondition condition, Options options, String requestId) {
        if (!REPORTS.containsKey(report)) throw ApiException.notFound("EXPORT_REPORT_UNKNOWN", "내보낼 수 없는 보고서입니다.");
        String title = REPORTS.get(report);
        var table = switch (report) {
            case "revenue" -> {
                var result = revenue.revenue(requester, condition);
                var rows = result.byPurpose().stream().map(p -> row(p.purpose(), p.approvedAmountKrw(), p.refundAmountKrw(),
                        p.netAmountKrw(), p.approvedPaymentCount(), p.refundCount(), p.dataStatus())).toList();
                yield table(title, result.aggregation(), true, prepend(text("용도"), AMOUNTS), rows, rows.size());
            }
            case "revenue-branches" -> {
                var result = revenue.compareBranches(requester, condition, options.sort(), options.direction());
                var rows = result.items().stream().map(b -> row(b.branchName(), b.approvedAmountKrw(), b.refundAmountKrw(),
                        b.netAmountKrw(), b.approvedPaymentCount(), b.refundCount(), b.dataStatus())).toList();
                yield table(title, result.aggregation(), true, prepend(text("지점"), AMOUNTS), rows, rows.size());
            }
            case "ledger" -> ledger(requester, title, condition, options);
            case "trend" -> {
                var result = trends.trend(requester, condition, options.measure());
                boolean money = !"passes".equals(result.measure());
                var columns = prepend(text("구간"), money ? AMOUNTS : VOLUME);
                var rows = result.items().stream().map(bucket -> money ? amounts(bucket.label(), bucket.revenue())
                        : volume(bucket.label(), bucket.passes())).toList();
                yield table(title, result.aggregation(), money, columns, rows, rows.size());
            }
            case "passes-volume" -> {
                var result = passes.volume(requester, condition);
                yield table(title, result.aggregation(), false, prepend(text("구분"), VOLUME), List.of(volume("합계", result.totals())), 1);
            }
            case "passes-products" -> products(requester, title, condition);
            default -> passUsage(requester, title, condition);
        };
        audit.record(AuditEvent.change(requester, null, "sales-report", null, "EXPORTED", null,
                json.writeValueAsString(auditSummary(report, condition, table)), requestId));
        return new Export(title, table);
    }

    private CsvFile.Table ledger(long requester, String title, AggregationCondition condition, Options options) {
        List<List<Object>> rows = new ArrayList<>();
        long total = 0;
        AggregationMeta meta = null;
        Map<Long, String> names = null;
        for (long page = 1; rows.size() < CsvFile.MAX_ROWS; page++) {
            var query = ListQuery.parse(page, PAGE, null, null, options.direction() == ListQuery.Direction.ASC ? "asc" : null,
                    Set.of("basisAt"), "basisAt");
            var result = revenue.ledger(requester, condition, options.entryType(), query);
            meta = result.aggregation();
            total = result.meta().total();
            if (names == null) names = branches.names(ids(meta.condition().branchIds()));
            for (var entry : result.items()) {
                rows.add(row(entry.entryType(), entry.basisAt(), names.getOrDefault(Long.parseLong(entry.branchId()), entry.branchId()),
                        entry.processingBranchId(), entry.purpose(), entry.productName(), entry.categoryName(), entry.passQuantity(),
                        entry.amountKrw(), entry.paymentId(), entry.refundId()));
            }
            if (result.items().size() < PAGE || rows.size() >= total) break;
        }
        var columns = List.of(text("구분"), text("기준 시각"), text("지점"), text("처리 지점 ID"), text("용도"), text("상품"),
                text("분류"), num("수량(개)"), num("금액(원)"), text("결제 ID"), text("환불 ID"));
        return table(title, meta, true, columns, rows, total);
    }

    private CsvFile.Table products(long requester, String title, AggregationCondition condition) {
        List<List<Object>> rows = new ArrayList<>();
        long total = 0;
        AggregationMeta meta = null;
        for (long page = 1; rows.size() < CsvFile.MAX_ROWS; page++) {
            var query = ListQuery.parse(page, PAGE, null, null, null, Set.of("netSoldQuantity"), "netSoldQuantity");
            var result = passes.products(requester, condition, query);
            meta = result.aggregation();
            total = result.meta().total();
            for (var item : result.items()) {
                rows.add(row(item.rank(), item.productName(), item.categoryName(), item.kind(), item.paymentCount(), item.soldQuantity(),
                        item.refundedQuantity(), item.netSoldQuantity(), item.issuedCount(), item.dataStatus()));
            }
            if (result.items().size() < PAGE || rows.size() >= total) break;
        }
        var columns = List.of(num("순위"), text("상품"), text("분류"), text("종류"), num("결제 수(건)"), num("판매 수량(개)"),
                num("환불 수량(개)"), num("순판매 수량(개)"), num("발급 수(개)"), text("데이터 상태"));
        return table(title, meta, false, columns, rows, total);
    }

    /** One row per using branch and sale branch; a sale branch the requester cannot see is only its label (M13-D12). */
    private CsvFile.Table passUsage(long requester, String title, AggregationCondition condition) {
        var result = usage.usage(requester, condition);
        var names = branches.names(ids(result.aggregation().condition().branchIds()));
        List<List<Object>> rows = new ArrayList<>();
        for (var branch : result.items()) {
            String used = names.getOrDefault(Long.parseLong(branch.branchId()), branch.branchId());
            for (var sale : branch.bySaleBranch()) {
                rows.add(row(used, sale.label(), sale.sessionCount(), sale.usedMinutes(), sale.usedCreditKrw()));
            }
        }
        var columns = List.of(text("사용 지점"), text("판매 지점"), num("이용 횟수(건)"), num("이용 시간(분)"), num("정액권 사용 금액(원)"));
        return table(title, result.aggregation(), false, columns, rows, rows.size());
    }

    private CsvFile.Table table(String title, AggregationMeta meta, boolean money, List<CsvFile.Column> columns,
            List<List<Object>> rows, long total) {
        return new CsvFile.Table(title, conditions(meta), money, meta.asOf(), columns, rows, total);
    }

    /** The screen's condition and its notes as one sentence; branches by the names the requester can see. */
    String conditions(AggregationMeta meta) {
        var c = meta.condition();
        var parts = new ArrayList<String>();
        parts.add("기간 " + CsvFile.period(c.from(), c.to()) + " (" + meta.timeZone() + ")");
        var names = branches.names(ids(c.branchIds()));
        parts.add("지점 " + (c.allBranches() ? "전체(" : "") + String.join(", ", c.branchIds().stream()
                .map(id -> names.getOrDefault(Long.parseLong(id), id)).toList()) + (c.allBranches() ? ")" : ""));
        if (c.refundBasis() != null) parts.add("환불 기준 " + ("paymentDate".equals(c.refundBasis()) ? "원결제일" : "처리일"));
        if (c.purpose() != null) parts.add("용도 " + c.purpose());
        if (c.productId() != null) parts.add("상품 ID " + c.productId());
        if (c.categoryCode() != null) parts.add("분류 " + c.categoryCode());
        if (c.granularity() != null) parts.add("단위 " + c.granularity());
        if (c.methods() != null && !c.methods().isEmpty()) parts.add("결제 수단 " + String.join(", ", c.methods()));
        if (c.productKinds() != null && !c.productKinds().isEmpty()) parts.add("상품 종류 " + String.join(", ", c.productKinds()));
        if (c.operatorIds() != null && !c.operatorIds().isEmpty()) parts.add("처리 직원 ID " + String.join(", ", c.operatorIds()));
        if (c.processingBranchIds() != null && !c.processingBranchIds().isEmpty()) {
            parts.add("처리 지점 ID " + String.join(", ", c.processingBranchIds()));
        }
        for (var note : meta.conditionNotes()) parts.add("참고: " + note.message());
        return String.join(" · ", parts);
    }

    private static Map<String, Object> auditSummary(String report, AggregationCondition condition, CsvFile.Table table) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("report", report);
        summary.put("from", condition.from() == null ? null : condition.from().toString());
        summary.put("to", condition.to() == null ? null : condition.to().toString());
        summary.put("branchIds", condition.branchIds());
        summary.put("advanced", condition.advanced());
        summary.put("rows", table.written());
        summary.put("truncated", table.truncated());
        return summary;
    }

    private static List<Long> ids(List<String> ids) { return ids.stream().map(Long::parseLong).toList(); }

    private static List<Object> amounts(String label, RevenueAmounts a) {
        return row(label, a.approvedAmountKrw(), a.refundAmountKrw(), a.netAmountKrw(), a.approvedPaymentCount(), a.refundCount(),
                a.dataStatus());
    }

    private static List<Object> volume(String label, PassVolume v) {
        return row(label, v.paymentCount(), v.soldQuantity(), v.refundedQuantity(), v.netSoldQuantity(), v.issuedCount(), v.dataStatus());
    }

    private static List<CsvFile.Column> prepend(CsvFile.Column first, List<CsvFile.Column> rest) {
        var columns = new ArrayList<CsvFile.Column>();
        columns.add(first);
        columns.addAll(rest);
        return columns;
    }

    private static List<Object> row(Object... values) { return java.util.Arrays.asList(values); }

    private static CsvFile.Column num(String name) { return new CsvFile.Column(name, true); }

    private static CsvFile.Column text(String name) { return new CsvFile.Column(name, false); }
}
