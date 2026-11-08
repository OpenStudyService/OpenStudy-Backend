package com.openstudy.modules.workforce.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Contract schema {@code LeaveBalance}: one employee's grants, whatever their origin branch (M12-D02). */
public record LeaveBalanceView(ActorSummary employee, Instant asOf, BigDecimal totalRemainingDays, List<LeaveBalanceGrantView> grants) {}
