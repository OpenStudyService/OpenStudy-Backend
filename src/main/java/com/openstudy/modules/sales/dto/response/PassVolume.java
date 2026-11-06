package com.openstudy.modules.sales.dto.response;

/**
 * Contract schema {@code PassVolume} (M9-D06): payment count (건), sold, refunded and net quantities (개) and the issued
 * count (개) kept apart. netSoldQuantity may be negative on processedDate; issuedCount keeps refunded passes.
 */
public record PassVolume(long paymentCount, long soldQuantity, long refundedQuantity, long netSoldQuantity,
        long issuedCount, String dataStatus) {}
