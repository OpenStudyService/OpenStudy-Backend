package com.openstudy.modules.workforce.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Contract schema {@code LeaveBalanceGrant}: a grant's period and days only; reason, granter and ledger stay in its detail. */
public record LeaveBalanceGrantView(String grantId, String originBranchId, int grantYear, LocalDate validFrom, LocalDate validThrough,
        BigDecimal grantedDays, BigDecimal remainingDays, boolean expired) {}
