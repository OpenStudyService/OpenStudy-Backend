package com.openstudy.modules.seat.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.seat.entity.SeatSessionMove;

public interface SeatSessionMoveRepository extends JpaRepository<SeatSessionMove, Long> {
    List<SeatSessionMove> findBySeatSessionIdOrderByMovedAtAscIdAsc(long seatSessionId);
}
