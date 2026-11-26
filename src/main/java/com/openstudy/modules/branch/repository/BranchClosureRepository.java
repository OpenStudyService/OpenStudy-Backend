package com.openstudy.modules.branch.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.branch.entity.BranchClosure;

public interface BranchClosureRepository extends JpaRepository<BranchClosure, Long> {

    @Query("SELECT c.branchId FROM BranchClosure c WHERE c.id = :id")
    Optional<Long> findBranchIdById(@Param("id") long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM BranchClosure c WHERE c.id = :id")
    Optional<BranchClosure> findForUpdateById(@Param("id") long id);

    Optional<BranchClosure> findByBranchIdAndClosedOn(long branchId, LocalDate closedOn);

    /** Both days included, in date order. */
    @Query("""
            SELECT c
            FROM BranchClosure c
            WHERE c.branchId IN :branches
              AND c.closedOn >= :from
              AND c.closedOn <= :to
            ORDER BY c.closedOn ASC, c.branchId ASC
            """)
    List<BranchClosure> findBetween(
            @Param("branches") Collection<Long> branches,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /**
     * Confirmed or in-use room reservations that start in [from, to): id, room id, room name, start, end and stored status.
     * Read by entity name so the branch module does not depend on the reservation module.
     */
    @Query("""
            SELECT r.id, r.roomId, m.name, r.startAt, r.endAt, CAST(r.status AS string)
            FROM RoomReservation r, Room m
            WHERE m.id = r.roomId
              AND r.branchId = :branch
              AND CAST(r.status AS string) IN ('CONFIRMED', 'IN_USE')
              AND r.startAt >= :from
              AND r.startAt < :to
            ORDER BY r.startAt ASC, r.id ASC
            """)
    List<Object[]> findReservationsStarting(
            @Param("branch") long branch,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Limit limit
    );

    @Query("""
            SELECT COUNT(r)
            FROM RoomReservation r
            WHERE r.branchId = :branch
              AND CAST(r.status AS string) IN ('CONFIRMED', 'IN_USE')
              AND r.startAt >= :from
              AND r.startAt < :to
            """)
    long countReservationsStarting(@Param("branch") long branch, @Param("from") Instant from, @Param("to") Instant to);

    @Query("SELECT e.id, e.name FROM Employee e WHERE e.id IN :ids")
    List<Object[]> findEmployeeNames(@Param("ids") Collection<Long> ids);

    @Query("SELECT b.id, b.name FROM Branch b WHERE b.id IN :ids")
    List<Object[]> findBranchNames(@Param("ids") Collection<Long> ids);
}
