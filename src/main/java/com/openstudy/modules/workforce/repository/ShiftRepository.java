package com.openstudy.modules.workforce.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.workforce.entity.Shift;
import com.openstudy.modules.workforce.entity.ShiftStatus;

public interface ShiftRepository extends JpaRepository<Shift, Long> {
    /** SELECT ... FOR UPDATE, for a change or a cancellation, after the employee row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Shift s WHERE s.id = :id")
    Optional<Shift> locked(@Param("id") long id);

    /**
     * The employee's shifts in this status at every branch that overlap [start, end), for a caller holding the employee
     * row. FOR SHARE on purpose: under REPEATABLE READ a plain SELECT answers from the transaction's first snapshot and
     * could miss the shift the employee lock waited for (workforce-api.md "트랜잭션 경계와 동시성").
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT s FROM Shift s WHERE s.employeeId = :employee AND s.status = :status AND s.startsAt < :end AND s.endsAt > :start "
            + "ORDER BY s.startsAt, s.id")
    List<Shift> overlapping(@Param("employee") long employee, @Param("status") ShiftStatus status, @Param("start") Instant start,
            @Param("end") Instant end);

    /**
     * The shift a clock-in at this instant belongs to: the employee's, at the clock-in branch, with startsAt ≤ at &lt; endsAt
     * (M8-D08). One employee's shifts never overlap, so there is at most one. FOR SHARE so that a cancellation still
     * being committed is waited for, and a clock-in never links to a shift that has just been cancelled.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT s FROM Shift s WHERE s.employeeId = :employee AND s.branchId = :branch AND s.status = :status "
            + "AND s.startsAt <= :at AND s.endsAt > :at ORDER BY s.startsAt, s.id")
    List<Shift> covering(@Param("employee") long employee, @Param("branch") long branch, @Param("status") ShiftStatus status,
            @Param("at") Instant at);

    /**
     * JPA-01 native exception: the employee module owns employees, and this is a step of the shift lock order, not a
     * read. Every write to one employee's shifts takes this row first, so writes for that employee run one at a time
     * whichever branch they come from (M8-D05). The row of a missing employee locks nothing and is refused later.
     */
    @Query(value = "SELECT id FROM employees WHERE id=:id FOR UPDATE", nativeQuery = true)
    List<Object> lockEmployee(@Param("id") long employeeId);

    /**
     * JPA-01 native exception: membership EXISTS over the employee and authorization tables, as a locking read after the
     * employee row lock. A shift's employee is an active account with an active membership at the shift's branch
     * (M8-D01, fk_shifts_1); an OWNER without a membership row is not one.
     */
    @Query(value = "SELECT e.id FROM employees e JOIN branch_memberships m ON m.employee_id=e.id "
            + "WHERE e.id=:employee AND e.status='ACTIVE' AND m.status='ACTIVE' AND m.branch_id=:branch FOR SHARE", nativeQuery = true)
    List<Object> activeMember(@Param("employee") long employeeId, @Param("branch") long branchId);
}
