package com.openstudy.modules.customerhistory.dto.response;

import java.time.Instant;
import java.util.List;
import com.openstudy.modules.ticket.dto.CrossBranchPass;

/** Contract schema {@code CrossBranchSummary}: the whole answer, so anything added here widens what POL-01 allows. */
public record CrossBranchSummary(String customerId, List<CrossBranchPass> passes, List<CrossBranchUsage> usages, Instant asOf) {}
