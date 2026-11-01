package com.openstudy.modules.ticket.service;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;

import com.openstudy.modules.ticket.dto.PassSettlement;
import com.openstudy.modules.ticket.entity.ProductKind;

/**
 * The deduction rules of seat-usage-api.md "계산 규칙" (M5-D02·D03) and nothing else: pure integer arithmetic, so every
 * contract example can be replayed without a database. No request value reaches this class.
 */
final class PassSettlementCalculator {
    private static final long NANOS_PER_MINUTE = Duration.ofMinutes(1).toNanos();
    private static final BigInteger SIXTY = BigInteger.valueOf(60);
    private static final BigInteger JS_MAX = BigInteger.valueOf(ProductInputs.JS_MAX);

    private PassSettlementCalculator() {}

    /**
     * @param in the effective check-in instant
     * @param out the effective check-out instant, not before {@code in}
     * @param expiresAt the pass expiry at check-out time, null when it never expires
     * @param remainingMinutes the time pass balance, null for other kinds
     * @param remainingCreditKrw the stored-value balance, null for other kinds
     * @param hourlyRateKrw the purchase-time rate, null for other kinds
     */
    static PassSettlement settle(ProductKind kind, Instant in, Instant out, Instant expiresAt, Long remainingMinutes,
            Long remainingCreditKrw, Long hourlyRateKrw) {
        long used = minutes(in, out);
        long beforeExpiry = expiresAt != null && out.isAfter(expiresAt) ? beforeExpiry(used, in, expiresAt) : used;
        long afterExpiry = used - beforeExpiry;
        return switch (kind) {
            case PERIOD -> new PassSettlement(used, 0, 0, afterExpiry, 0);
            case TIME -> {
                long deducted = Math.min(beforeExpiry, remainingMinutes);
                yield new PassSettlement(used, deducted, 0, afterExpiry + beforeExpiry - deducted, 0);
            }
            case STORED_VALUE -> {
                BigInteger charge = charge(beforeExpiry, hourlyRateKrw);
                long deducted = charge.min(BigInteger.valueOf(remainingCreditKrw)).longValueExact();
                // The rate may be as large as the API's integer limit; closing a session must never fail on it.
                long overage = charge.subtract(BigInteger.valueOf(deducted)).min(JS_MAX).longValueExact();
                yield new PassSettlement(used, 0, deducted, afterExpiry, overage);
            }
        };
    }

    /** A stay straddling the expiry counts its partial minute as valid; an expiry moved before check-in leaves none. */
    private static long beforeExpiry(long used, Instant in, Instant expiresAt) {
        return Math.max(0, Math.min(used, minutes(in, expiresAt)));
    }

    /** ceil(minutes × rate ÷ 60) won (M5-D02), in exact arithmetic because the product can exceed a long. */
    private static BigInteger charge(long minutes, long hourlyRateKrw) {
        var divided = BigInteger.valueOf(minutes).multiply(BigInteger.valueOf(hourlyRateKrw)).divideAndRemainder(SIXTY);
        return divided[1].signum() == 0 ? divided[0] : divided[0].add(BigInteger.ONE);
    }

    /** Whole minutes rounded up: 0 s is 0, 1~60 s is 1, 61 s is 2. */
    private static long minutes(Instant from, Instant to) {
        return Math.ceilDiv(Duration.between(from, to).toNanos(), NANOS_PER_MINUTE);
    }
}
