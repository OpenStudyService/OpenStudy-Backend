package com.openstudy.modules.seat.dto.response;

import java.util.List;

/** Contract schema {@code SeatZone}. */
public record SeatZone(String zone, List<SeatView> seats) {}
