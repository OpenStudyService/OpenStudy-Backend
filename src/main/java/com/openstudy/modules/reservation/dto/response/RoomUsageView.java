package com.openstudy.modules.reservation.dto.response;

import java.time.Instant;

/** Contract schema {@code RoomUsage}. Nothing writes room_usages until #70, so a reservation shows null for now. */
public record RoomUsageView(Instant startedAt, Instant endedAt, ActorSummary startedBy, ActorSummary endedBy) {}
