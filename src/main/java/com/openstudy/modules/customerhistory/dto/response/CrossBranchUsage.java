package com.openstudy.modules.customerhistory.dto.response;

import java.time.Instant;

/** Contract schema {@code CrossBranchUsage}: no record ID, handler or reason (POL-01). */
public record CrossBranchUsage(String kind, String branchId, Instant startAt, Instant endAt) {}
