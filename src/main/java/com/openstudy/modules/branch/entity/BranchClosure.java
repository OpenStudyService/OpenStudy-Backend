package com.openstudy.modules.branch.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A whole Asia/Seoul day on which a branch is closed (V12, M13-D01·D05). It refuses only new room reservations that start
 * on that day; everything else goes on. The day never changes: a closure is deleted and registered again instead.
 */
@Entity
@Table(name = "branch_closures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BranchClosure {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "branch_id", nullable = false, updatable = false)
    private long branchId;
    @Column(name = "closed_on", nullable = false, updatable = false)
    private LocalDate closedOn;
    @Column(length = 200)
    private String note;
    @Column(name = "created_by", nullable = false, updatable = false)
    private long createdBy;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    /** Exposed to clients as version + 1 and checked on every change. */
    @Version
    private long version;

    public static BranchClosure of(long branchId, LocalDate closedOn, String note, long createdBy) {
        var closure = new BranchClosure();
        closure.branchId = branchId;
        closure.closedOn = closedOn;
        closure.note = note;
        closure.createdBy = createdBy;
        return closure;
    }

    public void rewrite(String note) { this.note = note; }
}
