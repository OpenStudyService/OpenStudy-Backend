package com.openstudy.modules.facility.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.facility.entity.Facility;

public interface FacilityRepository extends JpaRepository<Facility, Long> {
    /**
     * Parent lock for AuthorizedCommands, taken after the guard/employee/branch locks. It is also what keeps the
     * facility's open inspection work single: every command that opens or closes one holds this row.
     * JPA-01 native exception: the lock order is part of the contract, so the statement stays explicit.
     */
    @Query(value = "SELECT id FROM facilities WHERE id = :id FOR UPDATE", nativeQuery = true)
    List<Long> lock(@Param("id") long id);

    /** SELECT ... FOR UPDATE: a locking read returns the latest commit, whatever the transaction's snapshot is. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Facility f WHERE f.id = :id")
    Optional<Facility> locked(@Param("id") long id);

    /** A change to the open work leaves the facility row otherwise untouched; the caller re-reads it afterwards. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Facility f SET f.version = f.version + 1 WHERE f.id = :id")
    int incrementVersion(@Param("id") long id);
}
