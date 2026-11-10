package com.openstudy.modules.sales.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Contract schema {@code DailyClosing} and its parts (operations-extension-api.md "일별 마감"). {@code difference} is the
 * current payment-date total minus the snapshot's, and the adjustments sum to its net amount.
 */
public record DailyClosingView(String id, String branchId, LocalDate businessDate, String note, ActorSummary closedBy, Instant closedAt,
        Snapshot snapshot, Current current, Amounts difference, List<Adjustment> adjustments, String timeZone, String currency) {

    /** Contract schema {@code ClosingAmounts}: the M9 metrics of one day. */
    public record Amounts(long approvedAmountKrw, long refundAmountKrw, long netAmountKrw, long approvedPaymentCount, long refundCount) {
        public Amounts minus(Amounts other) {
            return new Amounts(approvedAmountKrw - other.approvedAmountKrw, refundAmountKrw - other.refundAmountKrw,
                    netAmountKrw - other.netAmountKrw, approvedPaymentCount - other.approvedPaymentCount, refundCount - other.refundCount);
        }
    }

    /** Contract schema {@code ClosingPurposeAmounts}. */
    public record PurposeAmounts(String purpose, long approvedAmountKrw, long refundAmountKrw, long netAmountKrw,
            long approvedPaymentCount, long refundCount) {}

    /** Contract schema {@code ClosingMethodAmounts}: refunds are counted under the original payment's method. */
    public record MethodAmounts(String method, long approvedAmountKrw, long refundAmountKrw, long netAmountKrw,
            long approvedPaymentCount, long refundCount) {}

    /** Contract schema {@code ClosingBasisSnapshot}. */
    public record BasisSnapshot(Amounts totals, List<PurposeAmounts> byPurpose, List<MethodAmounts> byMethod) {}

    /** Contract schema {@code ClosingSnapshot}: both refund bases at closing time (M13-D08·D11). */
    public record Snapshot(BasisSnapshot processedDate, BasisSnapshot paymentDate) {}

    public record Current(Amounts paymentDate, Instant asOf) {}

    /** Contract schema {@code DailyClosingAdjustment}: the amount is negative; the reason needs payments:read. */
    public record Adjustment(String id, String refundId, String paymentId, long amountKrw, String reason, ActorSummary processedBy,
            Instant processedAt) {}

    /** Contract schema {@code ActorSummary}. */
    public record ActorSummary(String id, String name) {}

    /** Contract schema {@code DailyClosingSummary}: one row of the list. */
    public record Summary(String id, String branchId, LocalDate businessDate, ActorSummary closedBy, Instant closedAt,
            Amounts processedDate, long adjustmentCount) {}
}
