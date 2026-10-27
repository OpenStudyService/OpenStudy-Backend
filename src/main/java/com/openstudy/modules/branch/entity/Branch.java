package com.openstudy.modules.branch.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** Flyway owns the schema; this mapping only mirrors it and is checked by ddl-auto=validate. */
@Entity
@Table(name = "branches")
public class Branch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 32)
    private String code;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(nullable = false, length = 300)
    private String address;
    @Column(length = 30)
    private String phone;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private BranchStatus status;
    // Both timestamps are DB-maintained (DEFAULT / ON UPDATE CURRENT_TIMESTAMP(6)).
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    @Version
    private long version;
    @Column(name = "created_by")
    private Long createdBy;
    @Column(name = "updated_by")
    private Long updatedBy;
    @OneToMany(mappedBy = "branch", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("weekday")
    private List<BranchWeeklyHour> weeklyHours = new ArrayList<>();

    protected Branch() {}

    public Branch(String code, String name, String address, String phone, BranchStatus status, Long actor) {
        this.code = code; this.name = name; this.address = address; this.phone = phone; this.status = status;
        this.createdBy = actor; this.updatedBy = actor;
    }

    public void rename(String name, Long actor) { this.name = name; this.updatedBy = actor; }

    public void addWeeklyHour(int weekday, boolean closed, Integer opensMinute, Integer closesMinute) {
        weeklyHours.add(new BranchWeeklyHour(this, weekday, closed, opensMinute, closesMinute));
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getAddress() { return address; }
    public String getPhone() { return phone; }
    public BranchStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public Long getCreatedBy() { return createdBy; }
    public Long getUpdatedBy() { return updatedBy; }
    public List<BranchWeeklyHour> getWeeklyHours() { return List.copyOf(weeklyHours); }
}
