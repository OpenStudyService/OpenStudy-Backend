package com.openstudy.modules.reservation.dto.response;

import java.time.Instant;

/** Contract schema {@code RoomUsage}. A reservation that never started has none, so its {@code usage} is null. */
public record RoomUsageView(Instant startedAt, Instant endedAt, ActorSummary startedBy, ActorSummary endedBy) {}
