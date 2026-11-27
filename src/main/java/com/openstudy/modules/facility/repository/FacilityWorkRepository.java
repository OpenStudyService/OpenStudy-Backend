package com.openstudy.modules.facility.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.openstudy.modules.facility.entity.FacilityWork;
import com.openstudy.modules.facility.entity.FacilityWorkKind;
import com.openstudy.modules.facility.entity.FacilityWorkStatus;

public interface FacilityWorkRepository extends JpaRepository<FacilityWork, Long> {

    /** Open inspection works of these facilities, oldest first; the newest per facility is the one that counts. */
    @Query("""
            SELECT w FROM FacilityWork w
            WHERE w.facilityId IN :ids AND w.kind = :kind AND w.status IN :open
            ORDER BY w.id
            """)
    List<FacilityWork> findOpenOf(
            @Param("ids") Collection<Long> ids,
            @Param("kind") FacilityWorkKind kind,
            @Param("open") Collection<FacilityWorkStatus> open
    );

    /** Locking read for the facility being changed; the caller already holds the facility row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT w FROM FacilityWork w
            WHERE w.facilityId = :id AND w.kind = :kind AND w.status IN :open
            ORDER BY w.id
            """)
    List<FacilityWork> findLockedOpenOf(
            @Param("id") long id,
            @Param("kind") FacilityWorkKind kind,
            @Param("open") Collection<FacilityWorkStatus> open
    );

    Optional<FacilityWork> findFirstByFacilityIdAndKindAndStatusOrderByIdDesc(
            long facilityId,
            FacilityWorkKind kind,
            FacilityWorkStatus status
    );

    default List<FacilityWork> findOpenInspections(Collection<Long> facilityIds) {
        if (facilityIds.isEmpty()) {
            return List.of();
        }

        return findOpenOf(facilityIds, FacilityWorkKind.INSPECTION, openStatuses());
    }

    default List<FacilityWork> findLockedOpenInspections(long facilityId) {
        return findLockedOpenOf(facilityId, FacilityWorkKind.INSPECTION, openStatuses());
    }

    default Optional<FacilityWork> findLastFinishedInspection(long facilityId) {
        return findFirstByFacilityIdAndKindAndStatusOrderByIdDesc(
                facilityId,
                FacilityWorkKind.INSPECTION,
                FacilityWorkStatus.DONE
        );
    }

    private static List<FacilityWorkStatus> openStatuses() {
        return List.of(FacilityWorkStatus.OPEN, FacilityWorkStatus.IN_PROGRESS);
    }
}
