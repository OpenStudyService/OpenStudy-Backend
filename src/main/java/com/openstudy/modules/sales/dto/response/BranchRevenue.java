package com.openstudy.modules.sales.dto.response;

import java.util.List;

/** Contract schema {@code BranchRevenue}: one applied branch, 0 and noData when it had no source rows. */
public record BranchRevenue(String branchId, String branchName, long approvedAmountKrw, long refundAmountKrw,
        long netAmountKrw, long approvedPaymentCount, long refundCount, String dataStatus, List<PurposeRevenue> byPurpose) {
    public BranchRevenue { byPurpose = List.copyOf(byPurpose); }
}
