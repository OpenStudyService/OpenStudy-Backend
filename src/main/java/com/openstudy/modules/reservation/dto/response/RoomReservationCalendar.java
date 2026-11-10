package com.openstudy.modules.reservation.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Contract schema {@code RoomReservationCalendar}: the whole period, unpaged, with the branch's closed days (M13-D01). */
public record RoomReservationCalendar(Instant from, Instant to, List<LocalDate> closedDates, List<RoomReservationView> items) {}
