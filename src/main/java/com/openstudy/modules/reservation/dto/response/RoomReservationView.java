package com.openstudy.modules.reservation.dto.response;

import java.time.Instant;

import com.openstudy.modules.customer.dto.response.CustomerSummary;

/**
 * Contract schema {@code RoomReservation}, the one shape of the list, the calendar and the detail (FR-ROOM-05). billing
 * is null without payments:read or payments:write at the reservation's branch. canCancel is a display hint.
 */
public record RoomReservationView(String id, long version, String status, String branchId, RoomRef room, CustomerSummary customer,
        Instant startAt, Instant endAt, ReservationBilling billing, RoomUsageView usage, Instant cancelledAt, ActorSummary cancelledBy,
        String cancelReason, ActorSummary createdBy, Instant createdAt, boolean canCancel) {}
