package com.openstudy.modules.employee.entity;

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

/**
 * Flyway owns the schema; this mapping only mirrors it and is checked by ddl-auto=validate.
 * <p>owner_slot is a STORED generated column backing the single-owner UNIQUE key, so it is not mapped.</p>
 */
@Entity
@Table(name = "employees")
public class Employee {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 254, updatable = false)
    private String email;
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false, length = 100)
    private String nickname;
    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;
    @Enumerated(EnumType.STRING) @Column(name = "global_role", nullable = false)
    private GlobalRole globalRole;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private EmployeeStatus status;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Theme theme;
    @Column(name = "last_login_at")
    private Instant lastLoginAt;
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

    protected Employee() {}

    public Employee(String email, String passwordHash, String name, GlobalRole globalRole, EmployeeStatus status) {
        this.email = email; this.passwordHash = passwordHash; this.name = name; this.nickname = name;
        this.globalRole = globalRole; this.status = status; this.theme = Theme.FOREST;
    }

    /** The local owner created by the bootstrap command (M1-10); created_by/updated_by stay NULL as before. */
    public static Employee localOwner(String email, String passwordHash) {
        var owner = new Employee(email, passwordHash, "로컬 관리자", GlobalRole.OWNER, EmployeeStatus.ACTIVE);
        owner.nickname = "관리자";
        return owner;
    }

    public void changeNickname(String nickname) { this.nickname = nickname; this.updatedBy = id; }

    public void changeAccount(String name, EmployeeStatus status, long actor) {
        this.name = name; this.status = status; this.updatedBy = actor;
    }

    /** One of this employee's memberships changed; the caller advances the version. */
    public void touch(long actor) { this.updatedBy = actor; }

    /** Invitation approval: a PENDING account becomes ACTIVE. Only PENDING/ACTIVE accounts may be approved. */
    public boolean approveInvited(long actor) {
        if (status != EmployeeStatus.PENDING && status != EmployeeStatus.ACTIVE) return false;
        this.status = EmployeeStatus.ACTIVE; this.updatedBy = actor;
        return true;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getName() { return name; }
    public String getNickname() { return nickname; }
    public String getAvatarUrl() { return avatarUrl; }
    public GlobalRole getGlobalRole() { return globalRole; }
    public EmployeeStatus getStatus() { return status; }
    public Theme getTheme() { return theme; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public Long getCreatedBy() { return createdBy; }
    public Long getUpdatedBy() { return updatedBy; }
}
