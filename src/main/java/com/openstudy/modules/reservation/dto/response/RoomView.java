package com.openstudy.modules.reservation.dto.response;

import java.time.Instant;

/** Contract schema {@code Room}. The hourly rate is the published sale price, so every reader of the ledger sees it. */
public record RoomView(String id, long version, String branchId, String roomNo, String name, int capacity, long ratePerHourKrw,
        String status, Instant createdAt, Instant updatedAt) {}
