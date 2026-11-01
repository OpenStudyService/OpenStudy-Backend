package com.openstudy.modules.seat.dto.response;

import java.util.List;

/** Contract schema {@code SeatBoard}: the whole branch grouped by zone, with counts from the same seats. */
public record SeatBoard(SeatCounts counts, List<SeatZone> zones) {}
