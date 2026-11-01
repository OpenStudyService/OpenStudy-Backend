package com.openstudy.modules.seat.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.seat.entity.SeatSession;

public interface SeatSessionRepository extends JpaRepository<SeatSession, Long> {
    List<SeatSession> findByStatusAndSeatIdIn(SeatSession.Status status, Collection<Long> seatIds);

    boolean existsBySeatIdAndStatus(long seatId, SeatSession.Status status);
}
