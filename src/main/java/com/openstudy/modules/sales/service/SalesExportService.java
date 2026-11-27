package com.openstudy.modules.sales.service;

import java.util.ArrayList;
import java.util.Arrays;
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
import com.openstudy.modules.sales.dto.response.AggregationConditionView;
import com.openstudy.modules.sales.dto.response.AggregationMeta;
import com.openstudy.modules.sales.dto.response.BranchRevenueComparison;
import com.openstudy.modules.sales.dto.response.ConditionNote;
import com.openstudy.modules.sales.dto.response.PassProductSales;
import com.openstudy.modules.sales.dto.response.PassProductSalesPage;
import com.openstudy.modules.sales.dto.response.PassUsageByBranch;
import com.openstudy.modules.sales.dto.response.PassVolume;
import com.openstudy.modules.sales.dto.response.PassVolumeSummary;
import com.openstudy.modules.sales.dto.response.RevenueAmounts;
import com.openstudy.modules.sales.dto.response.RevenueSummary;
import com.openstudy.modules.sales.dto.response.SalesLedgerEntry;
import com.openstudy.modules.sales.dto.response.SalesLedgerPage;
import com.openstudy.modules.sales.dto.response.SalesTrend;

/**
 * #127 GET /sales/exports/{report} (analysis-reuse-api.md "CSV 내보내기"): the same services, conditions, permissions and
 * errors as the screen API of each report, written as a CSV table. Pages are read until the 1,000-row cap; every export
 * is audited with its conditions and row count, never with the data.
 */
@Service
@RequiredArgsConstructor
public class SalesExportService {

    private static final String REVENUE_REPORT = "revenue";
    private static final String REVENUE_BRANCHES_REPORT = "revenue-branches";
    private static final String LEDGER_REPORT = "ledger";
    private static final String TREND_REPORT = "trend";
    private static final String PASSES_VOLUME_REPORT = "passes-volume";
    private static final String PASSES_PRODUCTS_REPORT = "passes-products";
    private static final String PASSES_USAGE_REPORT = "passes-usage";

    /** report → title, in the order of the contract table. */
    public static final Map<String, String> REPORTS = new LinkedHashMap<>();

    static {
        REPORTS.put(REVENUE_REPORT, "매출 요약");
        REPORTS.put(REVENUE_BRANCHES_REPORT, "지점별 매출 비교");
        REPORTS.put(LEDGER_REPORT, "매출 원장");
        REPORTS.put(TREND_REPORT, "매출 추이");
        REPORTS.put(PASSES_VOLUME_REPORT, "이용권 판매량");
        REPORTS.put(PASSES_PRODUCTS_REPORT, "이용권 상품별 판매");
        REPORTS.put(PASSES_USAGE_REPORT, "이용권 사용 현황");
    }

    private static final int PAGE = 100;
    private static final String PASSES_MEASURE = "passes";
    private static final String PAYMENT_DATE_BASIS = "paymentDate";
    private static final String ASC = "asc";
    private static final String LEDGER_SORT = "basisAt";
    private static final String PRODUCTS_SORT = "netSoldQuantity";
    private static final String AUDIT_ENTITY = "sales-report";
    private static final String AUDIT_ACTION = "EXPORTED";
    private static final String CONDITION_SEPARATOR = " · ";
    private static final String LIST_SEPARATOR = ", ";
    private static final List<CsvFile.Column> AMOUNTS = List.of(
            toNumberColumn("승인 금액(원)"),
            toNumberColumn("환불 금액(원)"),
            toNumberColumn("순매출(원)"),
            toNumberColumn("승인 결제 수(건)"),
            toNumberColumn("환불 수(건)"),
            toTextColumn("데이터 상태")
    );
    private static final List<CsvFile.Column> VOLUME = List.of(
            toNumberColumn("결제 수(건)"),
            toNumberColumn("판매 수량(개)"),
            toNumberColumn("환불 수량(개)"),
            toNumberColumn("순판매 수량(개)"),
            toNumberColumn("발급 수(개)"),
            toTextColumn("데이터 상태")
    );

    private final SalesRevenueService salesRevenueService;
    private final PassSalesService passSalesService;
    private final SalesTrendService salesTrendService;
    private final PassUsageService passUsageService;
    private final SessionBranchService sessionBranchService;
    private final AuditTrail auditTrail;
    private final ObjectMapper objectMapper;

    /** The screen-only options of a report: the branch order, the ledger entry type, the trend measure. */
    public record Options(
            BranchRevenueSort sort,
            ListQuery.Direction direction,
            LedgerEntryType entryType,
            TrendMeasure measure
    ) {
    }

    public record Export(String name, CsvFile.Table table) {
    }

    @Transactional
    public Export export(
            long requester,
            String report,
            AggregationCondition condition,
            Options options,
            String requestId
    ) {
        if (!REPORTS.containsKey(report)) {
            throw ApiException.notFound("EXPORT_REPORT_UNKNOWN", "내보낼 수 없는 보고서입니다.");
        }

        String title = REPORTS.get(report);
        CsvFile.Table table = switch (report) {
            case REVENUE_REPORT -> createRevenueTable(requester, title, condition);
            case REVENUE_BRANCHES_REPORT -> createBranchesTable(requester, title, condition, options);
            case LEDGER_REPORT -> createLedgerTable(requester, title, condition, options);
            case TREND_REPORT -> createTrendTable(requester, title, condition, options);
            case PASSES_VOLUME_REPORT -> createVolumeTable(requester, title, condition);
            case PASSES_PRODUCTS_REPORT -> createProductsTable(requester, title, condition);
            default -> createPassUsageTable(requester, title, condition);
        };
        String auditDetail = objectMapper.writeValueAsString(createAuditSummary(report, condition, table));
        auditTrail.record(AuditEvent.change(
                requester,
                null,
                AUDIT_ENTITY,
                null,
                AUDIT_ACTION,
                null,
                auditDetail,
                requestId
        ));

        return new Export(title, table);
    }

    private CsvFile.Table createRevenueTable(long requester, String title, AggregationCondition condition) {
        RevenueSummary result = salesRevenueService.revenue(requester, condition);
        List<List<Object>> rows = result.byPurpose()
                .stream()
                .map(purpose -> toRow(
                        purpose.purpose(),
                        purpose.approvedAmountKrw(),
                        purpose.refundAmountKrw(),
                        purpose.netAmountKrw(),
                        purpose.approvedPaymentCount(),
                        purpose.refundCount(),
                        purpose.dataStatus()
                ))
                .toList();
        List<CsvFile.Column> columns = prepend(toTextColumn("용도"), AMOUNTS);

        return createTable(title, result.aggregation(), true, columns, rows, rows.size());
    }

    private CsvFile.Table createBranchesTable(
            long requester,
            String title,
            AggregationCondition condition,
            Options options
    ) {
        BranchRevenueComparison result = salesRevenueService.compareBranches(
                requester,
                condition,
                options.sort(),
                options.direction()
        );
        List<List<Object>> rows = result.items()
                .stream()
                .map(branch -> toRow(
                        branch.branchName(),
                        branch.approvedAmountKrw(),
                        branch.refundAmountKrw(),
                        branch.netAmountKrw(),
                        branch.approvedPaymentCount(),
                        branch.refundCount(),
                        branch.dataStatus()
                ))
                .toList();
        List<CsvFile.Column> columns = prepend(toTextColumn("지점"), AMOUNTS);

        return createTable(title, result.aggregation(), true, columns, rows, rows.size());
    }

    private CsvFile.Table createTrendTable(
            long requester,
            String title,
            AggregationCondition condition,
            Options options
    ) {
        SalesTrend result = salesTrendService.trend(requester, condition, options.measure());
        boolean money = !PASSES_MEASURE.equals(result.measure());
        List<CsvFile.Column> columns = prepend(toTextColumn("구간"), money ? AMOUNTS : VOLUME);
        List<List<Object>> rows = result.items()
                .stream()
                .map(bucket -> money
                        ? toAmountsRow(bucket.label(), bucket.revenue())
                        : toVolumeRow(bucket.label(), bucket.passes()))
                .toList();

        return createTable(title, result.aggregation(), money, columns, rows, rows.size());
    }

    private CsvFile.Table createVolumeTable(long requester, String title, AggregationCondition condition) {
        PassVolumeSummary result = passSalesService.volume(requester, condition);
        List<CsvFile.Column> columns = prepend(toTextColumn("구분"), VOLUME);
        List<List<Object>> rows = List.of(toVolumeRow("합계", result.totals()));

        return createTable(title, result.aggregation(), false, columns, rows, 1);
    }

    private CsvFile.Table createLedgerTable(
            long requester,
            String title,
            AggregationCondition condition,
            Options options
    ) {
        List<List<Object>> rows = new ArrayList<>();
        long total = 0;
        AggregationMeta meta = null;
        Map<Long, String> names = null;
        String direction = options.direction() == ListQuery.Direction.ASC ? ASC : null;

        for (long page = 1; rows.size() < CsvFile.MAX_ROWS; page++) {
            ListQuery query = ListQuery.parse(page, PAGE, null, null, direction, Set.of(LEDGER_SORT), LEDGER_SORT);
            SalesLedgerPage result = salesRevenueService.ledger(requester, condition, options.entryType(), query);
            meta = result.aggregation();
            total = result.meta().total();

            if (names == null) {
                names = sessionBranchService.names(toIds(meta.condition().branchIds()));
            }

            for (SalesLedgerEntry entry : result.items()) {
                String branchName = names.getOrDefault(Long.parseLong(entry.branchId()), entry.branchId());
                rows.add(toRow(
                        entry.entryType(),
                        entry.basisAt(),
                        branchName,
                        entry.processingBranchId(),
                        entry.purpose(),
                        entry.productName(),
                        entry.categoryName(),
                        entry.passQuantity(),
                        entry.amountKrw(),
                        entry.paymentId(),
                        entry.refundId()
                ));
            }

            boolean lastPage = result.items().size() < PAGE;
            boolean allRead = rows.size() >= total;

            if (lastPage || allRead) {
                break;
            }
        }

        List<CsvFile.Column> columns = List.of(
                toTextColumn("구분"),
                toTextColumn("기준 시각"),
                toTextColumn("지점"),
                toTextColumn("처리 지점 ID"),
                toTextColumn("용도"),
                toTextColumn("상품"),
                toTextColumn("분류"),
                toNumberColumn("수량(개)"),
                toNumberColumn("금액(원)"),
                toTextColumn("결제 ID"),
                toTextColumn("환불 ID")
        );

        return createTable(title, meta, true, columns, rows, total);
    }

    private CsvFile.Table createProductsTable(long requester, String title, AggregationCondition condition) {
        List<List<Object>> rows = new ArrayList<>();
        long total = 0;
        AggregationMeta meta = null;

        for (long page = 1; rows.size() < CsvFile.MAX_ROWS; page++) {
            ListQuery query = ListQuery.parse(page, PAGE, null, null, null, Set.of(PRODUCTS_SORT), PRODUCTS_SORT);
            PassProductSalesPage result = passSalesService.products(requester, condition, query);
            meta = result.aggregation();
            total = result.meta().total();

            for (PassProductSales item : result.items()) {
                rows.add(toRow(
                        item.rank(),
                        item.productName(),
                        item.categoryName(),
                        item.kind(),
                        item.paymentCount(),
                        item.soldQuantity(),
                        item.refundedQuantity(),
                        item.netSoldQuantity(),
                        item.issuedCount(),
                        item.dataStatus()
                ));
            }

            boolean lastPage = result.items().size() < PAGE;
            boolean allRead = rows.size() >= total;

            if (lastPage || allRead) {
                break;
            }
        }

        List<CsvFile.Column> columns = List.of(
                toNumberColumn("순위"),
                toTextColumn("상품"),
                toTextColumn("분류"),
                toTextColumn("종류"),
                toNumberColumn("결제 수(건)"),
                toNumberColumn("판매 수량(개)"),
                toNumberColumn("환불 수량(개)"),
                toNumberColumn("순판매 수량(개)"),
                toNumberColumn("발급 수(개)"),
                toTextColumn("데이터 상태")
        );

        return createTable(title, meta, false, columns, rows, total);
    }

    /** One row per using branch and sale branch; a sale branch the requester cannot see is only its label (M13-D12). */
    private CsvFile.Table createPassUsageTable(long requester, String title, AggregationCondition condition) {
        PassUsageByBranch result = passUsageService.usage(requester, condition);
        Map<Long, String> names = sessionBranchService.names(toIds(result.aggregation().condition().branchIds()));
        List<List<Object>> rows = new ArrayList<>();

        for (PassUsageByBranch.Branch branch : result.items()) {
            String used = names.getOrDefault(Long.parseLong(branch.branchId()), branch.branchId());

            for (PassUsageByBranch.SaleBranch sale : branch.bySaleBranch()) {
                rows.add(toRow(used, sale.label(), sale.sessionCount(), sale.usedMinutes(), sale.usedCreditKrw()));
            }
        }

        List<CsvFile.Column> columns = List.of(
                toTextColumn("사용 지점"),
                toTextColumn("판매 지점"),
                toNumberColumn("이용 횟수(건)"),
                toNumberColumn("이용 시간(분)"),
                toNumberColumn("정액권 사용 금액(원)")
        );

        return createTable(title, result.aggregation(), false, columns, rows, rows.size());
    }

    private CsvFile.Table createTable(
            String title,
            AggregationMeta meta,
            boolean money,
            List<CsvFile.Column> columns,
            List<List<Object>> rows,
            long total
    ) {
        return new CsvFile.Table(title, toConditionText(meta), money, meta.asOf(), columns, rows, total);
    }

    /** The screen's condition and its notes as one sentence; branches by the names the requester can see. */
    String toConditionText(AggregationMeta meta) {
        AggregationConditionView condition = meta.condition();
        List<String> parts = new ArrayList<>();
        parts.add("기간 " + CsvFile.period(condition.from(), condition.to()) + " (" + meta.timeZone() + ")");
        Map<Long, String> names = sessionBranchService.names(toIds(condition.branchIds()));
        List<String> branchNames = condition.branchIds()
                .stream()
                .map(id -> names.getOrDefault(Long.parseLong(id), id))
                .toList();
        String branchPrefix = condition.allBranches() ? "전체(" : "";
        String branchSuffix = condition.allBranches() ? ")" : "";
        parts.add("지점 " + branchPrefix + String.join(LIST_SEPARATOR, branchNames) + branchSuffix);

        if (condition.refundBasis() != null) {
            String basis = PAYMENT_DATE_BASIS.equals(condition.refundBasis()) ? "원결제일" : "처리일";
            parts.add("환불 기준 " + basis);
        }

        if (condition.purpose() != null) {
            parts.add("용도 " + condition.purpose());
        }

        if (condition.productId() != null) {
            parts.add("상품 ID " + condition.productId());
        }

        if (condition.categoryCode() != null) {
            parts.add("분류 " + condition.categoryCode());
        }

        if (condition.granularity() != null) {
            parts.add("단위 " + condition.granularity());
        }

        appendList(parts, "결제 수단 ", condition.methods());
        appendList(parts, "상품 종류 ", condition.productKinds());
        appendList(parts, "처리 직원 ID ", condition.operatorIds());
        appendList(parts, "처리 지점 ID ", condition.processingBranchIds());

        for (ConditionNote note : meta.conditionNotes()) {
            parts.add("참고: " + note.message());
        }

        return String.join(CONDITION_SEPARATOR, parts);
    }

    private static void appendList(List<String> parts, String prefix, List<String> values) {
        boolean present = values != null && !values.isEmpty();

        if (present) {
            parts.add(prefix + String.join(LIST_SEPARATOR, values));
        }
    }

    private static Map<String, Object> createAuditSummary(
            String report,
            AggregationCondition condition,
            CsvFile.Table table
    ) {
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

    private static List<Long> toIds(List<String> ids) {
        return ids.stream()
                .map(Long::parseLong)
                .toList();
    }

    private static List<Object> toAmountsRow(String label, RevenueAmounts amounts) {
        return toRow(
                label,
                amounts.approvedAmountKrw(),
                amounts.refundAmountKrw(),
                amounts.netAmountKrw(),
                amounts.approvedPaymentCount(),
                amounts.refundCount(),
                amounts.dataStatus()
        );
    }

    private static List<Object> toVolumeRow(String label, PassVolume volume) {
        return toRow(
                label,
                volume.paymentCount(),
                volume.soldQuantity(),
                volume.refundedQuantity(),
                volume.netSoldQuantity(),
                volume.issuedCount(),
                volume.dataStatus()
        );
    }

    private static List<CsvFile.Column> prepend(CsvFile.Column first, List<CsvFile.Column> rest) {
        List<CsvFile.Column> columns = new ArrayList<>();
        columns.add(first);
        columns.addAll(rest);

        return columns;
    }

    private static List<Object> toRow(Object... values) {
        return Arrays.asList(values);
    }

    private static CsvFile.Column toNumberColumn(String name) {
        return new CsvFile.Column(name, true);
    }

    private static CsvFile.Column toTextColumn(String name) {
        return new CsvFile.Column(name, false);
    }
}
