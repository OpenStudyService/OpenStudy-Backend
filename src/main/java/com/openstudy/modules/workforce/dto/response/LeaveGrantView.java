package com.openstudy.modules.workforce.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Contract schema {@code LeaveGrant}. canAdjust only guides the screen; every write is checked again. */
public record LeaveGrantView(String id, long version, ActorSummary employee, String originBranchId, int grantYear,
        LocalDate validFrom, LocalDate validThrough, BigDecimal grantedDays, BigDecimal remainingDays, boolean expired,
        String reason, ActorSummary grantedBy, Instant createdAt, boolean canAdjust) {}
