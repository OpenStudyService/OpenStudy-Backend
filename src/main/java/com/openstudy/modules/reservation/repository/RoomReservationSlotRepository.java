package com.openstudy.modules.reservation.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.reservation.entity.RoomReservationSlot;

public interface RoomReservationSlotRepository extends JpaRepository<RoomReservationSlot, Long> {
    /** A cancellation gives every hour back. */
    @Modifying
    @Query("DELETE FROM RoomReservationSlot s WHERE s.reservationId = :reservation")
    int deleteAllOf(@Param("reservation") long reservation);

    /** A completion gives back the hours that have not started: an hour starting exactly at {@code at} has started. */
    @Modifying
    @Query("DELETE FROM RoomReservationSlot s WHERE s.reservationId = :reservation AND s.slotStartAt > :at")
    int deleteStartingAfter(@Param("reservation") long reservation, @Param("at") Instant at);
}
