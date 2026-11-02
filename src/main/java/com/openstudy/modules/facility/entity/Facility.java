package com.openstudy.modules.facility.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Branch facility. The next inspection date is not stored: it follows from last_inspected_on, the interval and the
 * registration date (M7-D06), and is computed on read. The branch never changes and last_inspected_on moves only
 * through an inspection completion, never through a plain edit.
 */
@Entity
@Table(name = "facilities")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Facility {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "branch_id", nullable = false, updatable = false)
    private long branchId;
    @Column(name = "type_id", nullable = false)
    private long typeId;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(length = 200)
    private String location;
    @Column(columnDefinition = "text")
    private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private FacilityStatus status;
    @Column(name = "inspection_interval_days", nullable = false, columnDefinition = "smallint unsigned")
    private int inspectionIntervalDays;
    @Column(name = "last_inspected_on")
    private LocalDate lastInspectedOn;
    // Both timestamps are DB-maintained (DEFAULT / ON UPDATE CURRENT_TIMESTAMP(6)).
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    /** Exposed to clients and checked on every facility command. */
    @Version
    private long version;

    public Facility(long branchId, long typeId, String name, String location, String description, FacilityStatus status,
            int inspectionIntervalDays, LocalDate lastInspectedOn) {
        this.branchId = branchId; this.typeId = typeId; this.name = name; this.location = location;
        this.description = description; this.status = status; this.inspectionIntervalDays = inspectionIntervalDays;
        this.lastInspectedOn = lastInspectedOn;
    }

    public void change(long typeId, String name, String location, String description, FacilityStatus status,
            int inspectionIntervalDays) {
        this.typeId = typeId; this.name = name; this.location = location; this.description = description;
        this.status = status; this.inspectionIntervalDays = inspectionIntervalDays;
    }

    public void recordInspection(FacilityStatus status, LocalDate completedOn) {
        this.status = status; this.lastInspectedOn = completedOn;
    }
}
