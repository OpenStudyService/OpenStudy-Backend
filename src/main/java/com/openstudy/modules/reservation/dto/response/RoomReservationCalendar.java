package com.openstudy.modules.reservation.dto.response;

import java.time.Instant;
import java.util.List;

/** Contract schema {@code RoomReservationCalendar}: the whole period, unpaged. */
public record RoomReservationCalendar(Instant from, Instant to, List<RoomReservationView> items) {}
