package com.openstudy.modules.sales.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One branch's closing of one ended Asia/Seoul day (V15, M13-D04·D08·D11). The snapshot holds the M9 totals of the day
 * for both refund bases at closing time. A closing never changes and blocks nothing; later refunds of the day's payments
 * become adjustments.
 */
@Entity
@Table(name = "daily_closings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyClosing {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "branch_id", nullable = false, updatable = false)
    private long branchId;
    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;
    @Column(length = 500, updatable = false)
    private String note;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "snapshot_json", nullable = false, updatable = false)
    private String snapshotJson;
    @Column(name = "closed_by", nullable = false, updatable = false)
    private long closedBy;
    @Column(name = "closed_at", nullable = false, updatable = false)
    private Instant closedAt;

    public DailyClosing(long branchId, LocalDate businessDate, String note, String snapshotJson, long closedBy, Instant closedAt) {
        this.branchId = branchId; this.businessDate = businessDate; this.note = note; this.snapshotJson = snapshotJson;
        this.closedBy = closedBy; this.closedAt = closedAt;
    }
}
