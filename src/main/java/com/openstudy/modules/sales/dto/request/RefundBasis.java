package com.openstudy.modules.sales.dto.request;

import java.util.Arrays;

/** Where a refund is deducted (M9-D02). The wire value is the contract token; processedDate is the default. */
public enum RefundBasis {
    /** refunds.refunded_at: a closed period never changes afterwards. */
    PROCESSED_DATE("processedDate"),
    /** The original payments.approved_at: a later refund changes the period of the sale. */
    PAYMENT_DATE("paymentDate");

    public final String wire;
    RefundBasis(String wire) { this.wire = wire; }

    /** null when the token is not a contract value; the caller turns that into its own 422. */
    public static RefundBasis fromWire(String value) {
        return Arrays.stream(values()).filter(basis -> basis.wire.equals(value)).findFirst().orElse(null);
    }
}
