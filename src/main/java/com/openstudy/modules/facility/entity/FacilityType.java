package com.openstudy.modules.facility.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Global code table (no branch). Rows are only added: there is no update or delete (collaboration-api.md). */
@Entity
@Table(name = "facility_types")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FacilityType {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 80, updatable = false)
    private String name;
    @Column(name = "default_inspection_days", nullable = false, updatable = false, columnDefinition = "smallint unsigned")
    private int defaultInspectionDays;

    public FacilityType(String name, int defaultInspectionDays) {
        this.name = name; this.defaultInspectionDays = defaultInspectionDays;
    }
}
