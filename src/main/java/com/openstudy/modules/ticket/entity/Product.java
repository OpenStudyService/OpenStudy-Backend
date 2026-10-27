package com.openstudy.modules.ticket.entity;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * A pass product shared by its sale branches (Q7). The kind never changes (Q9); only that kind's term columns are
 * set and ck_products_1 rejects any other combination. Sale branches are plain branch ids: the branch module's
 * entity is never referenced (JPA-03).
 */
@Entity
@Table(name = "products")
public class Product {
    private static final String INT_UNSIGNED = "int unsigned";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 40, updatable = false)
    private String code;
    @Column(nullable = false, length = 120)
    private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false)
    private ProductKind kind;
    @Column(name = "price_krw", nullable = false)
    private long priceKrw;
    // INT UNSIGNED reaches 4,294,967,295 (ProductInputs.U32_MAX), beyond Integer, so these stay Long.
    @Column(name = "included_minutes", columnDefinition = INT_UNSIGNED)
    private Long includedMinutes;
    @Column(name = "period_days", columnDefinition = INT_UNSIGNED)
    private Long periodDays;
    @Column(name = "credit_krw")
    private Long creditKrw;
    @Column(name = "hourly_rate_krw")
    private Long hourlyRateKrw;
    @Column(name = "valid_days", columnDefinition = INT_UNSIGNED)
    private Long validDays;
    @Enumerated(EnumType.STRING) @Column(name = "sale_status", nullable = false)
    private SaleStatus saleStatus;
    // Both timestamps are DB-maintained (DEFAULT / ON UPDATE CURRENT_TIMESTAMP(6)).
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    /** Exposed to clients and checked with 409 on every product command (JPA-02). */
    @Version
    private long version;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "category_id", nullable = false)
    private ProductCategory category;
    @Column(name = "created_by", updatable = false)
    private Long createdBy;
    @Column(name = "updated_by")
    private Long updatedBy;
    @ElementCollection
    @CollectionTable(name = "product_branches", joinColumns = @JoinColumn(name = "product_id"))
    @Column(name = "branch_id", nullable = false)
    @OrderBy
    private Set<Long> branchIds = new LinkedHashSet<>();

    protected Product() {}

    public Product(String code, String name, ProductKind kind, long priceKrw, Terms terms, boolean onSale,
            ProductCategory category, Set<Long> branchIds, long actor) {
        this.code = code; this.kind = kind; this.createdBy = actor;
        change(name, priceKrw, terms, category, onSale, actor);
        this.branchIds.addAll(branchIds);
    }

    /** Term columns of one kind; the columns of the other kinds stay NULL. */
    public record Terms(Long includedMinutes, Long periodDays, Long creditKrw, Long hourlyRateKrw, Long validDays) {}

    public void change(String name, long priceKrw, Terms terms, ProductCategory category, boolean onSale, long actor) {
        this.name = name; this.priceKrw = priceKrw; this.category = category;
        this.includedMinutes = terms.includedMinutes(); this.periodDays = terms.periodDays();
        this.creditKrw = terms.creditKrw(); this.hourlyRateKrw = terms.hourlyRateKrw(); this.validDays = terms.validDays();
        this.saleStatus = onSale ? SaleStatus.ON_SALE : SaleStatus.STOPPED;
        this.updatedBy = actor;
    }

    public void replaceBranches(Set<Long> removed, Set<Long> added) {
        branchIds.removeAll(removed);
        branchIds.addAll(added);
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public ProductKind getKind() { return kind; }
    public long getPriceKrw() { return priceKrw; }
    public Long getIncludedMinutes() { return includedMinutes; }
    public Long getPeriodDays() { return periodDays; }
    public Long getCreditKrw() { return creditKrw; }
    public Long getHourlyRateKrw() { return hourlyRateKrw; }
    public Long getValidDays() { return validDays; }
    public SaleStatus getSaleStatus() { return saleStatus; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public ProductCategory getCategory() { return category; }
    public Long getCreatedBy() { return createdBy; }
    public Long getUpdatedBy() { return updatedBy; }
    /** Sale branch ids in ascending order. */
    public List<Long> getBranchIds() { return branchIds.stream().sorted().toList(); }
}
