package com.openstudy.modules.payment.dto.response;

import java.time.Instant;
import java.util.List;

import com.openstudy.common.web.PageResponse;

/**
 * Contract schema {@code PaymentPage}: a page of payments with the condition applied to the whole list and its totals by
 * status (FR-PAY-13, operations-extension-api.md "고급 필터", #116). The summary covers every matching row, not the page.
 */
public record PaymentPage(List<PaymentDetail> items, PageResponse.Meta meta, Filter filter, Summary summary) {
    public PaymentPage { items = List.copyOf(items); }

    /** Contract schema {@code PaymentFilter}: unset lists are empty. */
    public record Filter(List<String> branchIds, String status, Instant from, Instant to, String customerId, List<String> methods,
            List<String> productKinds, String categoryCode, List<String> operatorIds, List<String> processingBranchIds) {}

    /** Contract schema {@code PaymentListSummary}. */
    public record Summary(StatusTotal approved, StatusTotal failed, StatusTotal refunded) {}

    /** Contract schema {@code PaymentStatusTotal}. */
    public record StatusTotal(long count, long amountKrw) {}
}
