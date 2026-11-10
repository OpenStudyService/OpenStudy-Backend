package com.openstudy.modules.sales.dto.request;

import java.util.List;

/**
 * The M13 advanced filters (operations-extension-api.md "고급 필터", M13-D10), shared by payment lists and sales aggregations.
 * Stored values: methods CASH·CARD·TRANSFER·OTHER, productKinds TIME·PERIOD·STORED_VALUE. An empty list does not filter.
 * Approvals match the payment's method and creator; refunds the original payment's method, the refund's processor and the
 * refund's processing branch.
 */
public record AdvancedFilter(List<String> methods, List<String> productKinds, List<Long> operatorIds, List<Long> processingBranchIds) {
    public static final AdvancedFilter NONE = new AdvancedFilter(List.of(), List.of(), List.of(), List.of());

    public AdvancedFilter {
        methods = methods == null ? List.of() : List.copyOf(methods);
        productKinds = productKinds == null ? List.of() : List.copyOf(productKinds);
        operatorIds = operatorIds == null ? List.of() : List.copyOf(operatorIds);
        processingBranchIds = processingBranchIds == null ? List.of() : List.copyOf(processingBranchIds);
    }

    public boolean passOnly() { return !productKinds.isEmpty(); }
}
