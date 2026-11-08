package com.openstudy.modules.workforce.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Contract schema {@code LeaveGrantDetail}: a grant with its balance ledger in time order. */
public record LeaveGrantDetailView(String id, long version, ActorSummary employee, String originBranchId, int grantYear,
        LocalDate validFrom, LocalDate validThrough, BigDecimal grantedDays, BigDecimal remainingDays, boolean expired,
        String reason, ActorSummary grantedBy, Instant createdAt, boolean canAdjust, List<LeaveBalanceEventView> events) {}
