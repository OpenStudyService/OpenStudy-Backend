package com.openstudy.modules.workforce.dto.response;

import java.time.Instant;

/** Contract schema {@code ShiftConflictTarget}: a conflicting shift the requester may see, without its employee, break or version. */
public record ShiftConflictTarget(String id, String branchId, Instant startsAt, Instant endsAt) {}
