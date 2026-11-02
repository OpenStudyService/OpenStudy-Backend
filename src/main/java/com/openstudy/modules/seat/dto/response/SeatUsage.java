package com.openstudy.modules.seat.dto.response;

import java.time.Instant;

/** A closed stay as a customer's pass history needs it. Actor and reason belong to the stay's branch: see the caller. */
public record SeatUsage(long id, long passId, long branchId, Instant checkedOutAt, long usedMinutes, Long actorId, String reason) {}
