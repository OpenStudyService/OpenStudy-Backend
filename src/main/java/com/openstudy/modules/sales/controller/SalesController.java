package com.openstudy.modules.sales.controller;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.openstudy.common.exception.ApiException;
import com.openstudy.common.web.ListQuery;
import com.openstudy.modules.sales.dto.request.AdvancedFilter;
import com.openstudy.modules.sales.dto.response.PassUsageByBranch;
import com.openstudy.modules.sales.service.AggregationMetas;
import com.openstudy.modules.sales.dto.request.AggregationCondition;
import com.openstudy.modules.sales.dto.request.AggregationGranularity;
import com.openstudy.modules.sales.dto.request.BranchRevenueSort;
import com.openstudy.modules.sales.dto.request.LedgerEntryType;
import com.openstudy.modules.sales.dto.request.RefundBasis;
import com.openstudy.modules.sales.dto.request.SalesPurpose;
import com.openstudy.modules.sales.dto.request.TrendMeasure;
import com.openstudy.modules.sales.dto.response.BranchRevenueComparison;
import com.openstudy.modules.sales.dto.response.PassProductSalesPage;
import com.openstudy.modules.sales.dto.response.PassVolumeSummary;
import com.openstudy.modules.sales.dto.response.RevenueSummary;
import com.openstudy.modules.sales.dto.response.SalesLedgerPage;
import com.openstudy.modules.sales.dto.response.SalesTrend;
import com.openstudy.modules.sales.service.PassSalesService;
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
    private final SalesRevenueService service;
    private final PassSalesService passes;
    private final SalesTrendService trends;
    private final com.openstudy.modules.sales.service.PassUsageService usage;

    @GetMapping("/revenue")
    public RevenueSummary revenue(Authentication actor, HttpServletRequest request,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String refundBasis, @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String productId, @RequestParam(required = false) String categoryCode) {
        return service.revenue(actor(actor), condition(request, from, to, refundBasis, purpose, productId, categoryCode));
    }

    @GetMapping("/revenue/branches")
    public BranchRevenueComparison branches(Authentication actor, HttpServletRequest request,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String refundBasis, @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String productId, @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String sort, @RequestParam(required = false) String direction) {
        var condition = condition(request, from, to, refundBasis, purpose, productId, categoryCode);
        var order = sort == null ? BranchRevenueSort.NET_AMOUNT : BranchRevenueSort.fromWire(sort);
        if (order == null) throw ApiException.validation("sort", "지원하지 않는 정렬 항목입니다.");
        return service.compareBranches(actor(actor), condition, order, direction(direction));
    }

    @GetMapping("/ledger")
    public SalesLedgerPage ledger(Authentication actor, HttpServletRequest request,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String refundBasis, @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String productId, @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String entryType, @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String direction) {
        var condition = condition(request, from, to, refundBasis, purpose, productId, categoryCode);
        LedgerEntryType type = entryType == null ? null : LedgerEntryType.fromLabel(entryType);
        if (entryType != null && type == null) throw ApiException.validation("entryType", "허용되지 않은 값입니다.");
        // The order is fixed by the contract (basisAt, approval/refund, source ID); only the direction is chosen.
        var query = ListQuery.parse(page, pageSize, null, null, direction, Set.of("basisAt"), "basisAt");
        return service.ledger(actor(actor), condition, type, query);
    }

    /** Pass usage per using branch (#116, M13-D12): no refund basis and no purpose. */
    @GetMapping("/passes/usage")
    public PassUsageByBranch passUsage(Authentication actor, HttpServletRequest request,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String productId, @RequestParam(required = false) String categoryCode) {
        return usage.usage(actor(actor), condition(request, from, to, null, null, productId, categoryCode));
    }

    @GetMapping("/passes/volume")
    public PassVolumeSummary passVolume(Authentication actor, HttpServletRequest request,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String refundBasis, @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String productId, @RequestParam(required = false) String categoryCode) {
        return passes.volume(actor(actor), condition(request, from, to, refundBasis, purpose, productId, categoryCode));
    }

    @GetMapping("/passes/products")
    public PassProductSalesPage passProducts(Authentication actor, HttpServletRequest request,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String refundBasis, @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String productId, @RequestParam(required = false) String categoryCode,
            @RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") int pageSize) {
        var condition = condition(request, from, to, refundBasis, purpose, productId, categoryCode);
        // The order and the rank are fixed by the contract; there is no sort or direction to choose.
        var query = ListQuery.parse(page, pageSize, null, null, null, Set.of("netSoldQuantity"), "netSoldQuantity");
        return passes.products(actor(actor), condition, query);
    }

    @GetMapping("/trend")
    public SalesTrend trend(Authentication actor, HttpServletRequest request,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String refundBasis, @RequestParam(required = false) String granularity,
            @RequestParam(required = false) String measure, @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String productId, @RequestParam(required = false) String categoryCode) {
        AggregationGranularity bucket = granularity == null ? null : AggregationGranularity.fromWire(granularity);
        if (granularity != null && bucket == null) throw ApiException.validation("granularity", "허용되지 않은 값입니다.");
        TrendMeasure counted = measure == null ? null : TrendMeasure.fromWire(measure);
        if (measure != null && counted == null) throw ApiException.validation("measure", "허용되지 않은 값입니다.");
        var condition = condition(request, from, to, refundBasis, purpose, productId, categoryCode).withGranularity(bucket);
        return trends.trend(actor(actor), condition, counted);
    }

    /** Every filter is parsed for every operation, so the service can refuse the ones the operation does not take (422). */
    private static AggregationCondition condition(HttpServletRequest request, String from, String to, String refundBasis,
            String purpose, String productId, String categoryCode) {
        RefundBasis basis = refundBasis == null ? null : RefundBasis.fromWire(refundBasis);
        if (refundBasis != null && basis == null) throw ApiException.validation("refundBasis", "허용되지 않은 값입니다.");
        SalesPurpose selected = purpose == null ? null : SalesPurpose.fromLabel(purpose);
        if (purpose != null && selected == null) throw ApiException.validation("purpose", "허용되지 않은 값입니다.");
        return new AggregationCondition(branchIds(request), instant(from, "from"), instant(to, "to"), basis, selected,
                productId == null ? null : id(productId, "productId"), categoryCode, null, advanced(request));
    }

    /** The M13 advanced filters (M13-D10, #116): repeated parameters, contract labels in, stored values out. Duplicates are 422. */
    public static AdvancedFilter advanced(HttpServletRequest request) {
        return new AdvancedFilter(labels(request, "methods", AggregationMetas.METHOD_LABELS),
                labels(request, "productKinds", AggregationMetas.KIND_LABELS), ids(request, "operatorIds"), ids(request, "processingBranchIds"));
    }

    private static List<String> labels(HttpServletRequest request, String name, java.util.Map<String, String> stored) {
        String[] values = request.getParameterValues(name);
        if (values == null) return List.of();
        var result = new ArrayList<String>();
        for (String value : values) {
            String key = stored.entrySet().stream().filter(entry -> entry.getValue().equals(value)).map(java.util.Map.Entry::getKey)
                    .findFirst().orElseThrow(() -> ApiException.validation(name, "허용되지 않은 값입니다."));
            if (result.contains(key)) throw ApiException.validation(name, "같은 값을 두 번 보낼 수 없습니다.");
            result.add(key);
        }
        return result;
    }

    private static List<Long> ids(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) return List.of();
        var result = new ArrayList<Long>();
        for (String value : values) {
            long id = id(value, name);
            if (result.contains(id)) throw ApiException.validation(name, "같은 값을 두 번 보낼 수 없습니다.");
            result.add(id);
        }
        return result;
    }

    /** branchIds is a repeated parameter (branchIds=1&branchIds=2); each value is one ID string. */
    private static List<Long> branchIds(HttpServletRequest request) {
        String[] values = request.getParameterValues("branchIds");
        if (values == null) return null;
        var ids = new ArrayList<Long>();
        for (String value : values) ids.add(id(value, "branchIds"));
        return ids;
    }

    private static long id(String value, String field) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw ApiException.validation(field, "올바른 ID를 입력해 주세요.");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw ApiException.validation(field, "올바른 ID를 입력해 주세요.");
        }
    }

    /** ISO 8601 with an offset ("Z" or "+09:00"); a time without one is a format error (M9-D01). */
    private static Instant instant(String value, String field) {
        if (value == null) return null;
        if (value.length() <= 40) {
            try {
                return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
            } catch (DateTimeException exception) {
                // Reported below.
            }
        }
        throw ApiException.validation(field, "시간대 오프셋이 있는 ISO 8601 시각을 입력해 주세요.");
    }

    private static ListQuery.Direction direction(String value) {
        if (value == null || "desc".equals(value)) return ListQuery.Direction.DESC;
        if ("asc".equals(value)) return ListQuery.Direction.ASC;
        throw ApiException.validation("direction", "정렬 방향은 asc 또는 desc여야 합니다.");
    }

    private static long actor(Authentication authentication) { return Long.parseLong(authentication.getName()); }
}
