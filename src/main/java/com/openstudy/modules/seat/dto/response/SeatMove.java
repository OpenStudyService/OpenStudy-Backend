package com.openstudy.modules.seat.dto.response;

import java.time.Instant;

/** Contract schema {@code SeatMove}. */
public record SeatMove(SeatRef fromSeat, SeatRef toSeat, Instant movedAt, ActorSummary movedBy, String reason) {}
