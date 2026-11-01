package com.openstudy.modules.seat.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
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

    /** Uses the column's collation, exactly like uk_seats_1, so the check and the constraint agree. */
    boolean existsByBranchIdAndSeatNo(long branchId, String seatNo);

    boolean existsByBranchIdAndSeatNoAndIdNot(long branchId, String seatNo, long id);
}
