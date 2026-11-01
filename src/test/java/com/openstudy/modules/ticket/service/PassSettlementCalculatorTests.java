package com.openstudy.modules.ticket.service;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.openstudy.modules.ticket.dto.PassSettlement;
import com.openstudy.modules.ticket.entity.ProductKind;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #62: seat-usage-api.md "계산 규칙". The eight contract examples are replayed over HTTP in SeatSessionIntegrationTests. */
class PassSettlementCalculatorTests {
    static final Instant IN = Instant.parse("2026-11-02T10:00:00Z");

    static Instant at(String time) { return Instant.parse("2026-11-02T" + time + "Z"); }

    static PassSettlement time(Instant out, Instant expires, long remaining) {
        return PassSettlementCalculator.settle(ProductKind.TIME, IN, out, expires, remaining, null, null);
    }

    static PassSettlement stored(Instant out, Instant expires, long remaining, long rate) {
        return PassSettlementCalculator.settle(ProductKind.STORED_VALUE, IN, out, expires, null, remaining, rate);
    }

    static PassSettlement period(Instant out, Instant expires) {
        return PassSettlementCalculator.settle(ProductKind.PERIOD, IN, out, expires, null, null, null);
    }

    @Test void example1TimePassCoversTheStay() {
        assertThat(time(at("11:30:20"), null, 120)).isEqualTo(new PassSettlement(91, 91, 0, 0, 0));
    }

    @Test void example2TimePassRunsOutAndTheRestIsOverage() {
        assertThat(time(at("11:30:20"), null, 60)).isEqualTo(new PassSettlement(91, 60, 0, 31, 0));
    }

    @Test void example3StoredValueExactDivision() {
        assertThat(stored(at("11:30:20"), null, 10_000, 1_500)).isEqualTo(new PassSettlement(91, 0, 2_275, 0, 0));
    }

    @Test void example4StoredValueRoundsUpToTheNextWon() {
        assertThat(stored(at("11:30:20"), null, 10_000, 1_000)).isEqualTo(new PassSettlement(91, 0, 1_517, 0, 0));
    }

    @Test void example5StoredValueRunsOutAndTheRestIsOverageInWon() {
        assertThat(stored(at("11:30:20"), null, 500, 1_000)).isEqualTo(new PassSettlement(91, 0, 500, 0, 1_017));
    }

    @Test void example6PeriodPassRecordsOnlyTheMinutesAfterExpiry() {
        assertThat(period(at("11:00:00"), at("10:30:30"))).isEqualTo(new PassSettlement(60, 0, 0, 29, 0));
    }

    @Test void example7TimePassExpiringDuringTheStayChargesOnlyMinutesBeforeExpiry() {
        assertThat(time(at("11:00:00"), at("10:30:30"), 120)).isEqualTo(new PassSettlement(60, 31, 0, 29, 0));
    }

    @Test void example8ImmediateCheckOutCostsNothing() {
        assertThat(time(IN, null, 120)).isEqualTo(new PassSettlement(0, 0, 0, 0, 0));
    }

    @ParameterizedTest
    @CsvSource({"PT0S,0", "PT0.000001S,1", "PT1S,1", "PT59.999999S,1", "PT60S,1", "PT60.000001S,2", "PT61S,2", "PT120S,2", "PT121S,3"})
    void usedMinutesRoundUp(String stay, long minutes) {
        assertThat(period(IN.plus(java.time.Duration.parse(stay)), null).usedMinutes()).isEqualTo(minutes);
    }

    @Test void aBalanceEqualToTheCostIsUsedUpWithoutOverage() {
        assertThat(time(at("11:30:20"), null, 91)).isEqualTo(new PassSettlement(91, 91, 0, 0, 0));
        assertThat(time(at("11:30:20"), null, 92)).isEqualTo(new PassSettlement(91, 91, 0, 0, 0));
        assertThat(stored(at("11:30:20"), null, 1_517, 1_000)).isEqualTo(new PassSettlement(91, 0, 1_517, 0, 0));
        assertThat(stored(at("11:30:20"), null, 1_516, 1_000)).isEqualTo(new PassSettlement(91, 0, 1_516, 0, 1));
    }

    @Test void anExpiryAtTheCheckOutInstantDoesNotSplitTheStay() {
        // The split applies only when out > E; a stay ending exactly at expiry was entirely valid.
        assertThat(time(at("11:00:00"), at("11:00:00"), 120)).isEqualTo(new PassSettlement(60, 60, 0, 0, 0));
        assertThat(period(at("11:00:00"), at("11:00:00"))).isEqualTo(new PassSettlement(60, 0, 0, 0, 0));
        assertThat(period(at("10:59:59"), at("11:00:00"))).isEqualTo(new PassSettlement(60, 0, 0, 0, 0));
    }

    @Test void thePartialMinuteAcrossTheExpiryCountsAsValid() {
        // Same whole minute on both sides: everything is before expiry, nothing after.
        assertThat(time(at("10:30:50"), at("10:30:20"), 120)).isEqualTo(new PassSettlement(31, 31, 0, 0, 0));
        assertThat(time(at("10:31:50"), at("10:30:20"), 120)).isEqualTo(new PassSettlement(32, 31, 0, 1, 0));
    }

    @Test void aStoredValuePassChargesOnlyTheMinutesBeforeExpiryAndKeepsTheRestInMinutes() {
        // K = ceil(31 × 1000 ÷ 60) = 517; the 29 minutes after expiry are not charged.
        assertThat(stored(at("11:00:00"), at("10:30:30"), 10_000, 1_000)).isEqualTo(new PassSettlement(60, 0, 517, 29, 0));
        assertThat(stored(at("11:00:00"), at("10:30:30"), 100, 1_000)).isEqualTo(new PassSettlement(60, 0, 100, 29, 417));
    }

    @Test void aTimePassRunningOutBeforeExpiryCountsBothKindsOfOverage() {
        // D = min(31, 10) = 10; overage = A (29) + C − D (21).
        assertThat(time(at("11:00:00"), at("10:30:30"), 10)).isEqualTo(new PassSettlement(60, 10, 0, 50, 0));
    }

    @Test void anEmptyBalanceDeductsNothingAndKeepsEveryMinuteAsOverage() {
        assertThat(time(at("11:00:00"), null, 0)).isEqualTo(new PassSettlement(60, 0, 0, 60, 0));
        assertThat(stored(at("11:00:00"), null, 0, 600)).isEqualTo(new PassSettlement(60, 0, 0, 0, 600));
    }

    @Test void anExpiryMovedBeforeCheckInLeavesNoMinuteBeforeExpiry() {
        // A manual correction (M5-D04) may move the expiry into the past while the customer is seated.
        assertThat(time(at("11:00:00"), at("09:00:00"), 120)).isEqualTo(new PassSettlement(60, 0, 0, 60, 0));
        assertThat(stored(at("11:00:00"), at("09:00:00"), 500, 600)).isEqualTo(new PassSettlement(60, 0, 0, 60, 0));
        assertThat(period(at("11:00:00"), at("09:00:00"))).isEqualTo(new PassSettlement(60, 0, 0, 60, 0));
    }

    @Test void aHugeRateIsExactAndNeverBreaksCheckOut() {
        long rate = 9_007_199_254_740_991L;
        // ceil(2 × rate ÷ 60) = ceil(rate ÷ 30): rate = 30 × 300239975158033 + 1, so the won rounds up.
        var shortStay = stored(IN.plusSeconds(120), null, 1, rate);
        assertThat(shortStay.deductedKrw()).isEqualTo(1);
        assertThat(shortStay.overageKrw()).isEqualTo(300_239_975_158_033L);
        // Minutes × rate no longer fits a long: the overage is capped at the API's integer limit instead of failing.
        var longStay = stored(IN.plusSeconds(60 * 100_000), null, 1, rate);
        assertThat(longStay.overageKrw()).isEqualTo(rate);
        assertThat(longStay.deductedKrw()).isEqualTo(1);
    }
}
