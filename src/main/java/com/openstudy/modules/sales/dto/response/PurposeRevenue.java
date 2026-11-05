package com.openstudy.modules.sales.dto.response;

/** Contract schema {@code PurposeRevenue}: one purpose's subtotal, 0 and noData when it had no source rows. */
public record PurposeRevenue(String purpose, long approvedAmountKrw, long refundAmountKrw, long netAmountKrw,
        long approvedPaymentCount, long refundCount, String dataStatus) {}
