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
import com.openstudy.modules.sales.dto.request.AggregationCondition;
import com.openstudy.modules.sales.dto.request.BranchRevenueSort;
import com.openstudy.modules.sales.dto.request.LedgerEntryType;
import com.openstudy.modules.sales.dto.request.RefundBasis;
import com.openstudy.modules.sales.dto.request.SalesPurpose;
import com.openstudy.modules.sales.dto.response.BranchRevenueComparison;
import com.openstudy.modules.sales.dto.response.RevenueSummary;
import com.openstudy.modules.sales.dto.response.SalesLedgerPage;
import com.openstudy.modules.sales.service.SalesRevenueService;

/**
 * Revenue, branch comparison and minimal ledger (aggregation-api.md, #87). The controller only turns query strings into
 * an {@link AggregationCondition}; validation of the combination, defaults and authorization live in the service so M10
 * reaches the same rules without HTTP. There is no scope parameter: the branch selection is branchIds (M9-D07).
 */
@RestController
@RequestMapping("/api/sales")
@RequiredArgsConstructor
public class SalesController {
    private final SalesRevenueService service;

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

    /** productId and categoryCode are read only so the service can refuse them on a revenue operation (422). */
    private static AggregationCondition condition(HttpServletRequest request, String from, String to, String refundBasis,
            String purpose, String productId, String categoryCode) {
        RefundBasis basis = refundBasis == null ? null : RefundBasis.fromWire(refundBasis);
        if (refundBasis != null && basis == null) throw ApiException.validation("refundBasis", "허용되지 않은 값입니다.");
        SalesPurpose selected = purpose == null ? null : SalesPurpose.fromLabel(purpose);
        if (purpose != null && selected == null) throw ApiException.validation("purpose", "허용되지 않은 값입니다.");
        return new AggregationCondition(branchIds(request), instant(from, "from"), instant(to, "to"), basis, selected,
                productId == null ? null : id(productId, "productId"), categoryCode, null);
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
