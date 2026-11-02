package com.openstudy.modules.seat.dto.response;

import java.time.Instant;

/** A stay as the cross-branch summary may show it: where and when, with no end while it lasts. */
public record CustomerSeatUsage(long branchId, Instant startAt, Instant endAt) {}
