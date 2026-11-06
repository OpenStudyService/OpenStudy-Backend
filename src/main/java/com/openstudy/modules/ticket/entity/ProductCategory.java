package com.openstudy.modules.ticket.entity;

import java.time.Instant;

import com.openstudy.domainlens.api.semantic.SemanticEntity;
import com.openstudy.domainlens.api.semantic.SemanticField;
import com.openstudy.domainlens.api.semantic.SemanticPolicy;
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
@SemanticEntity(
        id = "product_category",
        name = "상품 분류",
        description = "이용권 통계 및 판매 분류",
        aliases = {"이용권 분류", "상품 카테고리"},
        domain = "passes"
)
@SemanticPolicy(
        readPolicy = "openstudy.read.ai",
        rowPolicy = "openstudy.globalScope",
        fieldPolicy = "openstudy.field.public"
)
public class ProductCategory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @SemanticField(id = "product_category.id", name = "상품 분류 ID", role = "ID", allowedOps = {"EQ", "NE", "IN"})
    private Long id;
    @Column(nullable = false, length = 40, updatable = false)
    private String code;
    @Column(nullable = false, length = 100)
    @SemanticField(id = "product_category.name", name = "상품 분류명", role = "LABEL", allowedOps = {"EQ", "NE", "IN"})
    private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false)
    private ProductKind kind;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    @SemanticField(id = "product_category.active", name = "상품 분류 활성 상태", role = "STATUS", allowedValues = {"ACTIVE", "INACTIVE"}, allowedOps = {"EQ", "NE", "IN"})
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
