package com.openstudy.modules.facility.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.openstudy.modules.facility.entity.FacilityType;

public interface FacilityTypeRepository extends JpaRepository<FacilityType, Long> {
    List<FacilityType> findAllByOrderByNameAscIdAsc();

    /** Uses the column's collation, exactly like uk_facility_types_1, so the check and the constraint agree. */
    boolean existsByName(String name);
}
