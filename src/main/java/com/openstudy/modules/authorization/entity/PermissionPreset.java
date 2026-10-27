package com.openstudy.modules.authorization.entity;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** A named permission set copied into invitations and memberships. System presets are read-only. */
@Entity
@Table(name = "permission_presets")
public class PermissionPreset {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false)
    private BranchRole role;
    @Column(name = "is_system", nullable = false, updatable = false)
    private boolean system;
    // Both timestamps are DB-maintained (DEFAULT / ON UPDATE CURRENT_TIMESTAMP(6)).
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    /** Exposed to clients and checked with 409 on update (JPA-02). */
    @Version
    private long version;
    @Column(name = "created_by", updatable = false)
    private Long createdBy;
    @Column(name = "updated_by")
    private Long updatedBy;
    /** Set semantics: Hibernate deletes and inserts only the changed grant rows, never the whole set. */
    @ManyToMany
    @JoinTable(name = "permission_preset_grants", joinColumns = @JoinColumn(name = "preset_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<PermissionDefinition> permissions = new LinkedHashSet<>();

    protected PermissionPreset() {}

    public PermissionPreset(String name, BranchRole role, Set<PermissionDefinition> permissions, long actor) {
        this.name = name; this.role = role; this.createdBy = actor;
        change(name, permissions, actor);
    }

    public void change(String name, Set<PermissionDefinition> permissions, long actor) {
        this.name = name; this.updatedBy = actor;
        this.permissions.retainAll(permissions);
        this.permissions.addAll(permissions);
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public BranchRole getRole() { return role; }
    public boolean isSystem() { return system; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public Long getCreatedBy() { return createdBy; }
    public Long getUpdatedBy() { return updatedBy; }
    /** Permission codes in ascending order. */
    public List<String> getPermissionCodes() {
        return permissions.stream().map(PermissionDefinition::getCode).sorted(Comparator.naturalOrder()).toList();
    }
}
