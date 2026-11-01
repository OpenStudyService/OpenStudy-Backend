package com.openstudy.modules.seat.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.seat.entity.Seat;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    /**
     * Parent lock for AuthorizedCommands, taken after the guard/employee/branch locks.
     * JPA-01 native exception: the lock order is part of the contract, so the statement stays explicit.
     */
    @Query(value = "SELECT id FROM seats WHERE id = :id FOR UPDATE", nativeQuery = true)
    List<Long> lock(@Param("id") long id);

    /** The branch alone, so the row is first loaded by {@link #locked} and always holds the current values. */
    @Query("SELECT s.branchId FROM Seat s WHERE s.id = :id")
    Optional<Long> branchOf(@Param("id") long id);

    /** SELECT ... FOR UPDATE: a locking read returns the latest commit, whatever the transaction's snapshot is. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Seat s WHERE s.id = :id")
    Optional<Seat> locked(@Param("id") long id);

    /** Uses the column's collation, exactly like uk_seats_1, so the check and the constraint agree. */
    boolean existsByBranchIdAndSeatNo(long branchId, String seatNo);

    boolean existsByBranchIdAndSeatNoAndIdNot(long branchId, String seatNo, long id);
}
