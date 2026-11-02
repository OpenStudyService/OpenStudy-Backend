package com.openstudy.modules.seat.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.seat.entity.SeatSession;

public interface SeatSessionRepository extends JpaRepository<SeatSession, Long> {
    List<SeatSession> findByStatusAndSeatIdIn(SeatSession.Status status, Collection<Long> seatIds);

    List<SeatSession> findByStatusAndCustomerIdIn(SeatSession.Status status, Collection<Long> customerIds);

    List<SeatSession> findByPassIdInAndStatusOrderByIdAsc(Collection<Long> passIds, SeatSession.Status status);

    boolean existsBySeatIdAndStatus(long seatId, SeatSession.Status status);

    boolean existsByCustomerIdAndStatus(long customerId, SeatSession.Status status);

    /** The branch alone, so the row is first loaded by {@link #locked} and always holds the current values. */
    @Query("SELECT s.branchId FROM SeatSession s WHERE s.id = :id")
    Optional<Long> branchOf(@Param("id") long id);

    /** SELECT ... FOR UPDATE: a locking read returns the latest commit, whatever the transaction's snapshot is. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SeatSession s WHERE s.id = :id")
    Optional<SeatSession> locked(@Param("id") long id);
}
