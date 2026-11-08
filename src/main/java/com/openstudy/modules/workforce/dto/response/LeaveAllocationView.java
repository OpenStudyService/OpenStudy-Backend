package com.openstudy.modules.workforce.dto.response;

import java.math.BigDecimal;

/** Contract schema {@code LeaveAllocation}: the days one approval took from one grant. */
public record LeaveAllocationView(String grantId, BigDecimal days, boolean restored) {}
