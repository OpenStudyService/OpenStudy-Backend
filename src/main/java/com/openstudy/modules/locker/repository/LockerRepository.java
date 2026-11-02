package com.openstudy.modules.locker.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.locker.entity.Locker;

public interface LockerRepository extends JpaRepository<Locker, Long> {
    /** The branch alone, so the row is first loaded by a locking read and always holds the current values. */
    @Query("SELECT l.branchId FROM Locker l WHERE l.id = :id")
    Optional<Long> branchOf(@Param("id") long id);

    /**
     * SELECT ... FOR UPDATE. Two assignments of one locker must wait for each other, because no constraint refuses
     * overlapping periods, so this is a write lock even when the row itself does not change.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Locker l WHERE l.id = :id")
    Optional<Locker> locked(@Param("id") long id);

    /** Every locker of a branch under FOR UPDATE, in id order like every other multi-row lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM Locker l WHERE l.branchId = :branch ORDER BY l.id")
    List<Locker> lockedInBranch(@Param("branch") long branch);

    /** Bumps every version, also where the rate stays the same: the command is the change (space-operations-api.md). */
    @Modifying
    @Query("UPDATE Locker l SET l.ratePer4WeeksKrw = :rate, l.version = l.version + 1 WHERE l.branchId = :branch")
    int applyRate(@Param("branch") long branch, @Param("rate") long rate);

    /** Uses the column's collation, exactly like uk_lockers_1, so the check and the constraint agree. */
    boolean existsByBranchIdAndLockerNo(long branchId, String lockerNo);

    boolean existsByBranchIdAndLockerNoAndIdNot(long branchId, String lockerNo, long id);
}
