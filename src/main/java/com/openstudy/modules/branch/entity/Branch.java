package com.openstudy.modules.branch.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.openstudy.domainlens.api.semantic.SemanticEntity;
import com.openstudy.domainlens.api.semantic.SemanticField;
import com.openstudy.domainlens.api.semantic.SemanticPolicy;
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
@SemanticEntity(
        id = "branch",
        name = "지점",
        description = "OpenStudy 운영 지점 원장",
        aliases = {"매장", "센터", "강남점", "성수점", "신규점"},
        domain = "branch"
)
@SemanticPolicy(
        readPolicy = "openstudy.read.ai",
        rowPolicy = "openstudy.branchScope",
        fieldPolicy = "openstudy.field.public"
)
public class Branch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @SemanticField(id = "branch.id", name = "지점 ID", role = "ID", allowedOps = {"EQ", "NE", "IN"})
    private Long id;
    @Column(nullable = false, length = 32)
    @SemanticField(id = "branch.code", name = "지점 코드", role = "CODE", aliases = {"OSDEMO-GN", "OSDEMO-SS", "OSDEMO-NW"}, allowedOps = {"EQ", "NE", "IN"})
    private String code;
    @Column(nullable = false, length = 120)
    @SemanticField(id = "branch.name", name = "지점명", role = "LABEL", aliases = {"지점 이름", "매장명", "강남점", "성수점", "신규점"}, allowedOps = {"EQ", "NE", "IN"})
    private String name;
    @Column(nullable = false, length = 300)
    private String address;
    @Column(length = 30)
    @SemanticField(id = "branch.phone", name = "지점 전화번호", exposed = false)
    private String phone;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    @SemanticField(id = "branch.status", name = "지점 운영 상태", role = "STATUS", allowedValues = {"ACTIVE", "INACTIVE"}, allowedOps = {"EQ", "NE", "IN"})
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

    public void update(String name, String address, String phone) {
        this.name = name; this.address = address; this.phone = phone;
    }

    public void changeStatus(BranchStatus status) { this.status = status; }

    /**
     * Sets one weekday in place. Rows are updated rather than deleted and re-inserted: Hibernate flushes inserts
     * before orphan deletes, which would collide with the (branch_id, weekday) unique key.
     */
    public void setWeeklyHour(int weekday, boolean closed, Integer opensMinute, Integer closesMinute) {
        for (var hour : weeklyHours) {
            if (hour.getWeekday() == weekday) { hour.change(closed, opensMinute, closesMinute); return; }
        }
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
