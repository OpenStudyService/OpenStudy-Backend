package com.openstudy.modules.facility.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * An inspection work of a facility. The one that is OPEN or IN_PROGRESS carries the assignee and the processing status
 * (M7-D03); finished ones are the inspection history. There is no unique constraint behind "one open work per
 * facility": the service holds the facility row lock while it opens or closes works.
 */
@Entity
@Table(name = "facility_works")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FacilityWork {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "facility_id", nullable = false, updatable = false)
    private long facilityId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false)
    private FacilityWorkKind kind;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private FacilityWorkStatus status;
    @Column(name = "result_note", columnDefinition = "text")
    private String resultNote;
    @Column(name = "assigned_employee_id")
    private Long assignedEmployeeId;
    @Column(name = "completed_by")
    private Long completedBy;
    @Generated(event = EventType.INSERT) @Column(name = "opened_at", insertable = false, updatable = false)
    private Instant openedAt;
    @Column(name = "completed_at")
    private Instant completedAt;

    private FacilityWork(long facilityId, FacilityWorkStatus status, Long assignee) {
        this.facilityId = facilityId; this.kind = FacilityWorkKind.INSPECTION; this.status = status;
        this.assignedEmployeeId = assignee;
    }

    public static FacilityWork open(long facilityId, Long assignee) {
        return new FacilityWork(facilityId, FacilityWorkStatus.OPEN, assignee);
    }

    /** An inspection that was already done when the facility was registered. */
    public static FacilityWork finished(long facilityId, Long assignee, String result, long by, Instant at) {
        var work = new FacilityWork(facilityId, FacilityWorkStatus.DONE, assignee);
        work.resultNote = result; work.completedBy = by; work.completedAt = at;
        return work;
    }

    public void assign(Long assignee) { this.assignedEmployeeId = assignee; }

    public void process(FacilityWorkStatus status) { this.status = status; }

    public void complete(String result, long by, Instant at) {
        this.status = FacilityWorkStatus.DONE; this.resultNote = result; this.completedBy = by; this.completedAt = at;
    }
}
