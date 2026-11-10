package com.openstudy.modules.reservation.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /** The branch alone, so the row is first loaded by a locking read and always holds the current values. */
    @Query("SELECT r.branchId FROM RoomReservation r WHERE r.id = :id")
    Optional<Long> branchOf(@Param("id") long id);

    /** SELECT ... FOR UPDATE, for a change of status. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RoomReservation r WHERE r.id = :id")
    Optional<RoomReservation> locked(@Param("id") long id);

    boolean existsByRoomIdAndStatus(long roomId, RoomReservationStatus status);

    /** The list's fixed order: latest start first. */
    List<RoomReservation> findByCustomerIdAndBranchIdInOrderByStartAtDescIdDesc(long customerId, Collection<Long> branches);

    /** The customer's reservations and those of the customers merged into it (M13-D07), newest first. */
    @Query("SELECT r FROM RoomReservation r WHERE r.customerId IN (SELECT m.id FROM Customer m WHERE m.id = :customerId OR m.mergedIntoCustomerId = :customerId) AND r.branchId IN :branches "
            + "ORDER BY r.startAt DESC, r.id DESC")
    List<RoomReservation> ofCustomerFamily(@Param("customerId") long customerId, @Param("branches") Collection<Long> branches);
}
