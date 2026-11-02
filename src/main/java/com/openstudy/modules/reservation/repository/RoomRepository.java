package com.openstudy.modules.reservation.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.reservation.entity.Room;

public interface RoomRepository extends JpaRepository<Room, Long> {
    /** The branch alone, so the row is first loaded by a locking read and always holds the current values. */
    @Query("SELECT r.branchId FROM Room r WHERE r.id = :id")
    Optional<Long> branchOf(@Param("id") long id);

    /** SELECT ... FOR UPDATE, for a change of the ledger row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Room r WHERE r.id = :id")
    Optional<Room> locked(@Param("id") long id);

    /** SELECT ... FOR SHARE: the rate and status a booking relies on cannot change before it commits. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT r FROM Room r WHERE r.id = :id")
    Optional<Room> lockedShared(@Param("id") long id);

    /** Uses the column's collation, exactly like uk_rooms_1, so the check and the constraint agree. */
    boolean existsByBranchIdAndRoomNo(long branchId, String roomNo);

    boolean existsByBranchIdAndRoomNoAndIdNot(long branchId, String roomNo, long id);
}
