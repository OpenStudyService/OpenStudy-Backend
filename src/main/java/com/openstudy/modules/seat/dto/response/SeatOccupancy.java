package com.openstudy.modules.seat.dto.response;

import java.util.List;

/** Contract schema {@code SeatOccupancy}: one item per branch in scope, by branch id. */
public record SeatOccupancy(List<SeatCounts> items) {}
