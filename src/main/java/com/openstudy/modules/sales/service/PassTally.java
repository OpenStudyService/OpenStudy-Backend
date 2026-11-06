package com.openstudy.modules.sales.service;

import java.util.Comparator;
import java.util.List;

import com.openstudy.modules.sales.dto.request.LedgerEntryType;
import com.openstudy.modules.sales.dto.response.PassVolume;
import com.openstudy.modules.sales.repository.SalesSourceRepository.PassTotal;
import com.openstudy.modules.ticket.dto.ProductTerms;

/**
 * Pass metrics (x-metric-definitions pass.*, M9-D06) of pass-total groups. Every bundle of a response (totals, a product
 * row, a trend bucket) is a sum of {@link Volume}s of the same groups, so the numbers of one response always agree.
 * Arithmetic is exact: an overflow fails rather than wraps.
 */
final class PassTally {
    /**
     * paymentCount (건) and soldQuantity (개) count approvals, refundedQuantity (개) and refundCount the refunds;
     * issuedCount (개) is the passes created for the approvals and stays when they are refunded. paymentCount +
     * refundCount is the number of pass source rows (approval and refund ledger items) behind the bundle.
     */
    record Volume(long paymentCount, long soldQuantity, long refundedQuantity, long issuedCount, long refundCount) {
        static final Volume ZERO = new Volume(0, 0, 0, 0, 0);

        static Volume of(PassTotal total) {
            return total.type() == LedgerEntryType.APPROVAL
                    ? new Volume(total.count(), total.quantity(), 0, total.issued(), 0)
                    : new Volume(0, 0, total.quantity(), 0, total.count());
        }

        static Volume sum(List<PassTotal> totals) {
            var sum = ZERO;
            for (var total : totals) sum = sum.plus(of(total));
            return sum;
        }

        Volume plus(Volume other) {
            return new Volume(Math.addExact(paymentCount, other.paymentCount), Math.addExact(soldQuantity, other.soldQuantity),
                    Math.addExact(refundedQuantity, other.refundedQuantity), Math.addExact(issuedCount, other.issuedCount),
                    Math.addExact(refundCount, other.refundCount));
        }

        /** May be negative on processedDate: an earlier sale refunded in this period. */
        long netSoldQuantity() { return Math.subtractExact(soldQuantity, refundedQuantity); }
        long sourceRows() { return Math.addExact(paymentCount, refundCount); }
        String dataStatus() { return sourceRows() == 0 ? RevenueTally.NO_DATA : RevenueTally.HAS_DATA; }

        PassVolume view() {
            return new PassVolume(paymentCount, soldQuantity, refundedQuantity, netSoldQuantity(), issuedCount, dataStatus());
        }
    }

    /** The row key of the product ranking: the sale-time snapshot copied into passes (FR-AGG-06). */
    record Snapshot(long productId, String productName, String categoryCode, String categoryName, String kind) {
        static Snapshot of(PassTotal total) {
            return new Snapshot(total.productId(), total.productName(), total.categoryCode(), total.categoryName(), total.kind());
        }

        /** passes.kind as the contract label; the kind of a product never changes after registration (Q9). */
        String kindLabel() {
            return switch (kind) {
                case "TIME" -> ProductTerms.TIME;
                case "PERIOD" -> ProductTerms.PERIOD;
                case "STORED_VALUE" -> ProductTerms.STORED_VALUE;
                default -> throw new IllegalStateException("Unknown pass kind " + kind);
            };
        }
    }

    /**
     * The fixed ranking order (aggregation-api.md "상품별 판매량·순위"): netSoldQuantity descending, then productId,
     * productName, categoryCode and categoryName ascending, text by Unicode code point. kind is functionally fixed by the
     * product and only closes the order.
     */
    static final Comparator<Ranked> ORDER = Comparator.comparingLong((Ranked row) -> row.volume().netSoldQuantity()).reversed()
            .thenComparingLong(row -> row.snapshot().productId())
            .thenComparing(row -> row.snapshot().productName(), PassTally::byCodePoint)
            .thenComparing(row -> row.snapshot().categoryCode(), PassTally::byCodePoint)
            .thenComparing(row -> row.snapshot().categoryName(), PassTally::byCodePoint)
            .thenComparing(row -> row.snapshot().kind(), PassTally::byCodePoint);

    record Ranked(Snapshot snapshot, Volume volume) {}

    private PassTally() {}

    /** String.compareTo orders UTF-16 code units, which differs from code points above U+FFFF. */
    static int byCodePoint(String left, String right) {
        int i = 0, j = 0;
        while (i < left.length() && j < right.length()) {
            int a = left.codePointAt(i), b = right.codePointAt(j);
            if (a != b) return Integer.compare(a, b);
            i += Character.charCount(a);
            j += Character.charCount(b);
        }
        return Boolean.compare(i < left.length(), j < right.length());
    }
}
