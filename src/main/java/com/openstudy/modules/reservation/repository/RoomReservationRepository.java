package com.openstudy.modules.reservation.repository;

import java.time.Instant;
import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.reservation.entity.RoomReservation;
import com.openstudy.modules.reservation.entity.RoomReservationStatus;

public interface RoomReservationRepository extends JpaRepository<RoomReservation, Long> {
    /** Two half-open periods overlap when each starts before the other ends. */
    @Query("SELECT COUNT(r) > 0 FROM RoomReservation r WHERE r.roomId = :room AND r.status IN :statuses "
            + "AND r.startAt < :end AND r.endAt > :start")
    boolean existsOverlapping(@Param("room") long room, @Param("statuses") Collection<RoomReservationStatus> statuses,
            @Param("start") Instant start, @Param("end") Instant end);
}
