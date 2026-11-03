package com.openstudy.modules.reservation.dto.response;

import java.time.Instant;

/** A room use as the cross-branch summary may show it: where and when, with no end while it lasts. */
public record CustomerRoomUsage(long branchId, Instant startAt, Instant endAt) {}
