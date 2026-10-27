package com.openstudy.modules.ticket.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CurrentTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * Immutable product snapshot per version; purchases reference it as their purchase-time terms.
 * changed_at has no column default: like the former INSERT it takes the database's CURRENT_TIMESTAMP.
 */
@Entity
@Table(name = "product_revisions")
public class ProductRevision {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "product_id", updatable = false)
    private Long productId;
    @Column(name = "original_product_id", nullable = false, updatable = false)
    private Long originalProductId;
    @Column(name = "product_version", nullable = false, updatable = false)
    private long productVersion;
    @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false, updatable = false)
    private RevisionEvent eventType;
    @Column(name = "changed_by", updatable = false)
    private Long changedBy;
    @CurrentTimestamp(event = EventType.INSERT, source = SourceType.DB)
    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;
    @Column(name = "request_id", nullable = false, length = 128, updatable = false)
    private String requestId;
    @Column(length = 500, updatable = false)
    private String reason;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "snapshot_json", nullable = false, updatable = false)
    private String snapshotJson;

    protected ProductRevision() {}

    public ProductRevision(long productId, long productVersion, RevisionEvent eventType, long actor, String requestId,
            String snapshotJson) {
        this.productId = productId; this.originalProductId = productId; this.productVersion = productVersion;
        this.eventType = eventType; this.changedBy = actor; this.requestId = requestId; this.snapshotJson = snapshotJson;
    }

    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public Long getOriginalProductId() { return originalProductId; }
    public long getProductVersion() { return productVersion; }
    public RevisionEvent getEventType() { return eventType; }
    public Long getChangedBy() { return changedBy; }
    public Instant getChangedAt() { return changedAt; }
    public String getRequestId() { return requestId; }
    public String getReason() { return reason; }
    public String getSnapshotJson() { return snapshotJson; }
}
