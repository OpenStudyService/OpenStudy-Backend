package com.openstudy.modules.sales.dto.response;

/**
 * Contract schema {@code PassProductSales}: one sale-time product snapshot (product ID, name, category code and name).
 * rank is the competition rank of netSoldQuantity; the tie order never splits a rank.
 */
public record PassProductSales(long rank, String productId, String productName, String categoryCode, String categoryName,
        String kind, long paymentCount, long soldQuantity, long refundedQuantity, long netSoldQuantity, long issuedCount,
        String dataStatus) {}
