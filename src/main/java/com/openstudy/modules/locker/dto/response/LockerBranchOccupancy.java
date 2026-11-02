package com.openstudy.modules.locker.dto.response;

import java.time.Instant;

/** Contract schema {@code LockerBranchOccupancy}: inUse + available + blocked = total, and overdue is part of inUse. */
public record LockerBranchOccupancy(String branchId, long total, long inUse, long available, long blocked, long overdue, Instant asOf) {}
