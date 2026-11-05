package com.openstudy.modules.sales.dto.response;

/** Contract schema {@code RevenueAmounts}: won integers and counts. netAmountKrw may be negative (processedDate). */
public record RevenueAmounts(long approvedAmountKrw, long refundAmountKrw, long netAmountKrw, long approvedPaymentCount,
        long refundCount, String dataStatus) {}
