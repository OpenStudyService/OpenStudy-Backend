package com.openstudy.modules.sales.controller;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.export.CsvFile;
import com.openstudy.common.web.ApiRequestFilter;
import com.openstudy.common.web.ListQuery;
import com.openstudy.modules.sales.dto.request.AdvancedFilter;
import com.openstudy.modules.sales.dto.request.AggregationCondition;
import com.openstudy.modules.sales.dto.request.AggregationGranularity;
import com.openstudy.modules.sales.dto.request.BranchRevenueSort;
import com.openstudy.modules.sales.dto.request.LedgerEntryType;
import com.openstudy.modules.sales.dto.request.RefundBasis;
import com.openstudy.modules.sales.dto.request.SalesPurpose;
import com.openstudy.modules.sales.dto.request.TrendMeasure;
import com.openstudy.modules.sales.dto.response.BranchRevenueComparison;
import com.openstudy.modules.sales.dto.response.PassProductSalesPage;
import com.openstudy.modules.sales.dto.response.PassUsageByBranch;
import com.openstudy.modules.sales.dto.response.PassVolumeSummary;
import com.openstudy.modules.sales.dto.response.RevenueSummary;
import com.openstudy.modules.sales.dto.response.SalesLedgerPage;
import com.openstudy.modules.sales.dto.response.SalesTrend;
import com.openstudy.modules.sales.service.AggregationMetas;
import com.openstudy.modules.sales.service.PassSalesService;
import com.openstudy.modules.sales.service.PassUsageService;
import com.openstudy.modules.sales.service.SalesExportService;
import com.openstudy.modules.sales.service.SalesRevenueService;
import com.openstudy.modules.sales.service.SalesTrendService;

/**
 * Revenue, branch comparison and minimal ledger (#87), pass volume, product ranking and the trend (#88) of
 * aggregation-api.md. The controller only turns query strings into an {@link AggregationCondition}; validation of the
 * combination, defaults and authorization live in the services so M10 reaches the same rules without HTTP. There is no
 * scope parameter: the branch selection is branchIds (M9-D07). Every operation reads purpose, productId and categoryCode
 * so the service can refuse the ones it does not take with a 422 instead of ignoring them.
 */
@RestController
@RequestMapping("/api/sales")
@RequiredArgsConstructor
public class SalesController {

    private static final Set<String> LEDGER_SORT_FIELDS = Set.of("basisAt");
    private static final String LEDGER_DEFAULT_SORT = "basisAt";
    private static final Set<String> PRODUCT_SORT_FIELDS = Set.of("netSoldQuantity");
    private static final String PRODUCT_DEFAULT_SORT = "netSoldQuantity";
    private static final String USAGE_REPORT = "passes-usage";
    private static final String TREND_REPORT = "trend";
    private static final String ID_FORMAT = "[1-9][0-9]{0,18}";
    private static final int MAX_INSTANT_LENGTH = 40;
    private static final String NOT_ALLOWED_MESSAGE = "허용되지 않은 값입니다.";
    private static final String UNSUPPORTED_SORT_MESSAGE = "지원하지 않는 정렬 항목입니다.";
    private static final String DUPLICATE_VALUE_MESSAGE = "같은 값을 두 번 보낼 수 없습니다.";
    private static final String INVALID_ID_MESSAGE = "올바른 ID를 입력해 주세요.";

    private final SalesRevenueService salesRevenueService;
    private final PassSalesService passSalesService;
    private final SalesTrendService salesTrendService;
    private final PassUsageService passUsageService;
    private final SalesExportService salesExportService;

    @GetMapping("/revenue")
    public RevenueSummary getRevenueSummary(
            Authentication actor,
            HttpServletRequest request,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String refundBasis,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String categoryCode
    ) {
        long actorId = actorId(actor);
        AggregationCondition condition = toCondition(request, from, to, refundBasis, purpose, productId, categoryCode);

        return salesRevenueService.revenue(actorId, condition);
    }

    @GetMapping("/revenue/branches")
    public BranchRevenueComparison compareBranchRevenue(
            Authentication actor,
            HttpServletRequest request,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String refundBasis,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction
    ) {
        AggregationCondition condition = toCondition(request, from, to, refundBasis, purpose, productId, categoryCode);
        BranchRevenueSort order = parseSort(sort);
        long actorId = actorId(actor);

        return salesRevenueService.compareBranches(actorId, condition, order, parseDirection(direction));
    }

    @GetMapping("/ledger")
    public SalesLedgerPage listSalesLedger(
            Authentication actor,
            HttpServletRequest request,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String refundBasis,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String entryType,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String direction
    ) {
        AggregationCondition condition = toCondition(request, from, to, refundBasis, purpose, productId, categoryCode);
        LedgerEntryType type = parseEntryType(entryType);
        // The order is fixed by the contract (basisAt, approval/refund, source ID); only the direction is chosen.
        ListQuery query = ListQuery.parse(page, pageSize, null, null, direction, LEDGER_SORT_FIELDS,
                LEDGER_DEFAULT_SORT);

        return salesRevenueService.ledger(actorId(actor), condition, type, query);
    }

    /** Pass usage per using branch (#116, M13-D12): no refund basis and no purpose. */
    @GetMapping("/passes/usage")
    public PassUsageByBranch getPassUsageByBranch(
            Authentication actor,
            HttpServletRequest request,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String categoryCode
    ) {
        long actorId = actorId(actor);
        AggregationCondition condition = toCondition(request, from, to, null, null, productId, categoryCode);

        return passUsageService.usage(actorId, condition);
    }

    @GetMapping("/passes/volume")
    public PassVolumeSummary getPassSalesVolume(
            Authentication actor,
            HttpServletRequest request,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String refundBasis,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String categoryCode
    ) {
        long actorId = actorId(actor);
        AggregationCondition condition = toCondition(request, from, to, refundBasis, purpose, productId, categoryCode);

        return passSalesService.volume(actorId, condition);
    }

    @GetMapping("/passes/products")
    public PassProductSalesPage listPassProductSales(
            Authentication actor,
            HttpServletRequest request,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String refundBasis,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize
    ) {
        AggregationCondition condition = toCondition(request, from, to, refundBasis, purpose, productId, categoryCode);
        // The order and the rank are fixed by the contract; there is no sort or direction to choose.
        ListQuery query = ListQuery.parse(page, pageSize, null, null, null, PRODUCT_SORT_FIELDS, PRODUCT_DEFAULT_SORT);

        return passSalesService.products(actorId(actor), condition, query);
    }

    @GetMapping("/trend")
    public SalesTrend getSalesTrend(
            Authentication actor,
            HttpServletRequest request,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String refundBasis,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false) String measure,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String categoryCode
    ) {
        AggregationGranularity bucket = parseGranularity(granularity);
        TrendMeasure counted = parseMeasure(measure);
        AggregationCondition condition = toCondition(request, from, to, refundBasis, purpose, productId, categoryCode)
                .withGranularity(bucket);

        return salesTrendService.trend(actorId(actor), condition, counted);
    }

    /**
     * #127: a report as CSV with the same query, permissions and errors as its screen API (analysis-reuse-api.md). There
     * is no page query: the file holds up to 1,000 rows of the whole result.
     */
    @GetMapping("/exports/{report}")
    public ResponseEntity<byte[]> exportSalesReport(
            Authentication actor,
            HttpServletRequest request,
            @PathVariable String report,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String refundBasis,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String entryType,
            @RequestParam(required = false) String granularity,
            @RequestParam(required = false) String measure
    ) {
        if (!SalesExportService.REPORTS.containsKey(report)) {
            throw ApiException.notFound("EXPORT_REPORT_UNKNOWN", "내보낼 수 없는 보고서입니다.");
        }

        for (String paging : List.of("page", "pageSize")) {
            if (request.getParameter(paging) != null) {
                throw ApiException.validation(paging, "내보내기는 페이지 없이 전체 결과를 담습니다.");
            }
        }

        // Each report reads only the query its screen API reads; the usage report takes no refund basis or purpose.
        boolean usageReport = report.equals(USAGE_REPORT);
        AggregationCondition condition = toCondition(
                request,
                from,
                to,
                usageReport ? null : refundBasis,
                usageReport ? null : purpose,
                productId,
                categoryCode
        );
        BranchRevenueSort order = parseSort(sort);
        LedgerEntryType type = parseEntryType(entryType);
        AggregationGranularity bucket = parseGranularity(granularity);
        TrendMeasure counted = parseMeasure(measure);

        if (report.equals(TREND_REPORT)) {
            condition = condition.withGranularity(bucket);
        }

        Instant createdAt = Instant.now();
        SalesExportService.Options options = new SalesExportService.Options(order, parseDirection(direction), type,
                counted);
        SalesExportService.Export export = salesExportService.export(actorId(actor), report, condition, options,
                ApiRequestFilter.requestId(request));

        return CsvFile.response(export.name(), createdAt, CsvFile.write(export.table(), createdAt));
    }

    /** The M13 advanced filters (M13-D10, #116): repeated parameters, contract labels in, stored values out. Duplicates are 422. */
    public static AdvancedFilter advanced(HttpServletRequest request) {
        List<String> methods = parseLabels(request, "methods", AggregationMetas.METHOD_LABELS);
        List<String> productKinds = parseLabels(request, "productKinds", AggregationMetas.KIND_LABELS);
        List<Long> operatorIds = parseIds(request, "operatorIds");
        List<Long> processingBranchIds = parseIds(request, "processingBranchIds");

        return new AdvancedFilter(methods, productKinds, operatorIds, processingBranchIds);
    }

    /** Every filter is parsed for every operation, so the service can refuse the ones the operation does not take (422). */
    private static AggregationCondition toCondition(
            HttpServletRequest request,
            String from,
            String to,
            String refundBasis,
            String purpose,
            String productId,
            String categoryCode
    ) {
        RefundBasis basis = refundBasis == null ? null : RefundBasis.fromWire(refundBasis);

        if (refundBasis != null && basis == null) {
            throw ApiException.validation("refundBasis", NOT_ALLOWED_MESSAGE);
        }

        SalesPurpose selected = purpose == null ? null : SalesPurpose.fromLabel(purpose);

        if (purpose != null && selected == null) {
            throw ApiException.validation("purpose", NOT_ALLOWED_MESSAGE);
        }

        List<Long> branchIds = parseBranchIds(request);
        Instant start = parseInstant(from, "from");
        Instant end = parseInstant(to, "to");
        Long product = productId == null ? null : parseId(productId, "productId");

        return new AggregationCondition(branchIds, start, end, basis, selected, product, categoryCode, null,
                advanced(request));
    }

    private static BranchRevenueSort parseSort(String sort) {
        BranchRevenueSort order = sort == null ? BranchRevenueSort.NET_AMOUNT : BranchRevenueSort.fromWire(sort);

        if (order == null) {
            throw ApiException.validation("sort", UNSUPPORTED_SORT_MESSAGE);
        }

        return order;
    }

    private static LedgerEntryType parseEntryType(String entryType) {
        LedgerEntryType type = entryType == null ? null : LedgerEntryType.fromLabel(entryType);

        if (entryType != null && type == null) {
            throw ApiException.validation("entryType", NOT_ALLOWED_MESSAGE);
        }

        return type;
    }

    private static AggregationGranularity parseGranularity(String granularity) {
        AggregationGranularity bucket = granularity == null ? null : AggregationGranularity.fromWire(granularity);

        if (granularity != null && bucket == null) {
            throw ApiException.validation("granularity", NOT_ALLOWED_MESSAGE);
        }

        return bucket;
    }

    private static TrendMeasure parseMeasure(String measure) {
        TrendMeasure counted = measure == null ? null : TrendMeasure.fromWire(measure);

        if (measure != null && counted == null) {
            throw ApiException.validation("measure", NOT_ALLOWED_MESSAGE);
        }

        return counted;
    }

    private static List<String> parseLabels(HttpServletRequest request, String name, Map<String, String> stored) {
        String[] values = request.getParameterValues(name);

        if (values == null) {
            return List.of();
        }

        List<String> result = new ArrayList<>();

        for (String value : values) {
            String key = findStoredValue(stored, value)
                    .orElseThrow(() -> ApiException.validation(name, NOT_ALLOWED_MESSAGE));

            if (result.contains(key)) {
                throw ApiException.validation(name, DUPLICATE_VALUE_MESSAGE);
            }

            result.add(key);
        }

        return result;
    }

    private static Optional<String> findStoredValue(Map<String, String> stored, String label) {
        return stored.entrySet()
                .stream()
                .filter(entry -> entry.getValue().equals(label))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private static List<Long> parseIds(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);

        if (values == null) {
            return List.of();
        }

        List<Long> result = new ArrayList<>();

        for (String value : values) {
            long id = parseId(value, name);

            if (result.contains(id)) {
                throw ApiException.validation(name, DUPLICATE_VALUE_MESSAGE);
            }

            result.add(id);
        }

        return result;
    }

    /** branchIds is a repeated parameter (branchIds=1&branchIds=2); each value is one ID string. */
    private static List<Long> parseBranchIds(HttpServletRequest request) {
        String[] values = request.getParameterValues("branchIds");

        if (values == null) {
            return null;
        }

        List<Long> ids = new ArrayList<>();

        for (String value : values) {
            ids.add(parseId(value, "branchIds"));
        }

        return ids;
    }

    private static long parseId(String value, String field) {
        if (value == null || !value.matches(ID_FORMAT)) {
            throw ApiException.validation(field, INVALID_ID_MESSAGE);
        }

        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw ApiException.validation(field, INVALID_ID_MESSAGE);
        }
    }

    /** ISO 8601 with an offset ("Z" or "+09:00"); a time without one is a format error (M9-D01). */
    private static Instant parseInstant(String value, String field) {
        if (value == null) {
            return null;
        }

        if (value.length() <= MAX_INSTANT_LENGTH) {
            try {
                return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            } catch (DateTimeException exception) {
                // Reported below.
            }
        }

        throw ApiException.validation(field, "시간대 오프셋이 있는 ISO 8601 시각을 입력해 주세요.");
    }

    private static ListQuery.Direction parseDirection(String value) {
        if (value == null || "desc".equals(value)) {
            return ListQuery.Direction.DESC;
        }

        if ("asc".equals(value)) {
            return ListQuery.Direction.ASC;
        }

        throw ApiException.validation("direction", "정렬 방향은 asc 또는 desc여야 합니다.");
    }

    private static long actorId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }
}
