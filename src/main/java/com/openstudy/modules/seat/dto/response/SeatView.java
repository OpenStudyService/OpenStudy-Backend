package com.openstudy.modules.seat.dto.response;

import java.time.Instant;

/** Contract schema {@code Seat}. {@code status} is the displayed state, not the stored one. */
public record SeatView(String id, long version, String branchId, String zone, String seatNo, String kind, String status,
        ActiveSessionView activeSession, Instant createdAt) {}
