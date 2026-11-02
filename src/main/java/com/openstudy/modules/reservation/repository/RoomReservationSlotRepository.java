package com.openstudy.modules.reservation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.reservation.entity.RoomReservationSlot;

public interface RoomReservationSlotRepository extends JpaRepository<RoomReservationSlot, Long> {}
