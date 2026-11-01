package com.openstudy.modules.seat.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Contract schema {@code SeatCounts}. {@code occupancyRate} is null when the branch has no seat ("해당 없음"), which is
 * different from 0.0 (seats exist, none in use).
 */
public record SeatCounts(String branchId, long total, long inUse, long available, long blocked, BigDecimal occupancyRate,
        Instant asOf) {}
