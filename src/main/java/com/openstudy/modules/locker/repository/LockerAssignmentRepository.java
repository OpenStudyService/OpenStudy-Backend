package com.openstudy.modules.locker.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.locker.entity.LockerAssignment;

public interface LockerAssignmentRepository extends JpaRepository<LockerAssignment, Long> {
    /** An assignment has no branch column: it belongs to the branch of its locker. */
    @Query("SELECT l.branchId FROM LockerAssignment a JOIN Locker l ON l.id = a.lockerId WHERE a.id = :id")
    Optional<Long> branchOf(@Param("id") long id);

    /** SELECT ... FOR UPDATE, for an extension or a return. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM LockerAssignment a WHERE a.id = :id")
    Optional<LockerAssignment> locked(@Param("id") long id);

    /**
     * What holds a period on this locker, for a caller that has locked the locker. FOR SHARE on purpose: a locking read
     * always sees the latest commit, while a plain SELECT under REPEATABLE READ answers from the snapshot of the
     * transaction's first plain read. If such a read ever came before the locker lock, it could miss the very assignment
     * the lock waited for, and nothing else would refuse the overlap.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT a FROM LockerAssignment a WHERE a.lockerId = :locker AND a.status = " + LockerOccupation.ACTIVE)
    List<LockerAssignment> holding(@Param("locker") long locker);

    /**
     * The assignments occupying these lockers today, oldest first. Periods of one locker do not overlap, so when an
     * overdue assignment and the next one have both started, the overdue one comes first: its things are still inside.
     */
    @Query("SELECT a FROM LockerAssignment a WHERE a.lockerId IN :lockers AND " + LockerOccupation.STARTED + " ORDER BY a.startDate, a.id")
    List<LockerAssignment> occupying(@Param("lockers") Collection<Long> lockers, @Param("today") LocalDate today);

    /** What the customer actually held or holds, latest start first: a failed or cancelled assignment held nothing. */
    @Query("SELECT a FROM LockerAssignment a WHERE a.customerId = :customer AND a.startDate <= :today AND (a.status = " + LockerOccupation.ACTIVE
            + " OR a.status = " + LockerOccupation.RETURNED + ") ORDER BY a.startDate DESC, a.id DESC")
    List<LockerAssignment> heldBy(@Param("customer") long customer, @Param("today") LocalDate today);
}
