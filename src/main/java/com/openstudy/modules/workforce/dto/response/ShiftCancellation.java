package com.openstudy.modules.workforce.dto.response;

import java.time.Instant;

/** Contract schema {@code ShiftCancellation}, read from the shift's CANCELLED history row. reason may be null. */
public record ShiftCancellation(Instant cancelledAt, ActorSummary cancelledBy, String reason) {}
