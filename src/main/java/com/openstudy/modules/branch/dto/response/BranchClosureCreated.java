package com.openstudy.modules.branch.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Contract schema {@code BranchClosureCreated}: the closure and the reservations it warns about. Existing reservations
 * are never cancelled (M13-D01); the list names no customer.
 */
public record BranchClosureCreated(BranchClosureView closure, List<AffectedReservation> affectedReservations,
        long affectedReservationCount) {

    /** Contract schema {@code AffectedReservation}. */
    public record AffectedReservation(String id, String roomId, String roomName, Instant startAt, Instant endAt, String status) {}
}
