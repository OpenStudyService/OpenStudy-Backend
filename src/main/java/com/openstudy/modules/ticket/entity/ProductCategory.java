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
import jakarta.persistence.Version;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** Statistics category shared by every branch. code and kind are immutable (FR-PROD-07). */
@Entity
@Table(name = "product_categories")
public class ProductCategory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 40, updatable = false)
    private String code;
    @Column(nullable = false, length = 100)
    private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false)
    private ProductKind kind;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private CategoryStatus status;
    // Both timestamps are DB-maintained (DEFAULT / ON UPDATE CURRENT_TIMESTAMP(6)).
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    @Column(name = "created_by", updatable = false)
    private Long createdBy;
    @Column(name = "updated_by")
    private Long updatedBy;
    /** Exposed to clients and checked with 409 on update (JPA-02). */
    @Version
    private long version;

    protected ProductCategory() {}

    public ProductCategory(String code, String name, ProductKind kind, long actor) {
        this.code = code; this.name = name; this.kind = kind; this.status = CategoryStatus.ACTIVE;
        this.createdBy = actor; this.updatedBy = actor;
    }

    public void change(String name, CategoryStatus status, long actor) {
        this.name = name; this.status = status; this.updatedBy = actor;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public ProductKind getKind() { return kind; }
    public CategoryStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getCreatedBy() { return createdBy; }
    public Long getUpdatedBy() { return updatedBy; }
    public long getVersion() { return version; }
}
