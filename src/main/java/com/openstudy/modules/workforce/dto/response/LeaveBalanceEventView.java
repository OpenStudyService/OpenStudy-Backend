package com.openstudy.modules.workforce.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/** Contract schema {@code LeaveBalanceEvent}: one immutable ledger row (부여·보정·사용·복원). */
public record LeaveBalanceEventView(String type, BigDecimal deltaDays, String leaveRequestId, String reason, ActorSummary actor,
        Instant createdAt) {}
