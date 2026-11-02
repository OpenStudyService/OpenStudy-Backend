package com.openstudy.modules.reservation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.reservation.entity.RoomUsage;

public interface RoomUsageRepository extends JpaRepository<RoomUsage, Long> {
    Optional<RoomUsage> findByReservationId(long reservationId);

    List<RoomUsage> findByReservationIdIn(Collection<Long> reservationIds);
}
