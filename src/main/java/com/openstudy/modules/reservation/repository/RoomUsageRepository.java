package com.openstudy.modules.reservation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.reservation.dto.response.CustomerRoomUsage;
import com.openstudy.modules.reservation.entity.RoomUsage;

public interface RoomUsageRepository extends JpaRepository<RoomUsage, Long> {
    Optional<RoomUsage> findByReservationId(long reservationId);

    List<RoomUsage> findByReservationIdIn(Collection<Long> reservationIds);

    /** A use has no customer or branch of its own: both are its reservation's. Latest first. */
    @Query("SELECT new com.openstudy.modules.reservation.dto.response.CustomerRoomUsage(r.branchId, u.startedAt, u.endedAt) "
            + "FROM RoomUsage u JOIN RoomReservation r ON r.id = u.reservationId WHERE r.customerId = :customer "
            + "ORDER BY u.startedAt DESC, u.id DESC")
    List<CustomerRoomUsage> latestOf(@Param("customer") long customer, Limit limit);
}
