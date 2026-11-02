package com.openstudy.modules.notice.entity;

import java.time.Instant;
import java.util.HashSet;
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
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Internal notice (announcements). There is no delete and no read receipt. The target branches live in
 * announcement_branches and are empty for a notice addressed to everyone. {@code publishedAt} is set whenever the notice
 * is (re)published and kept when it is archived, so the list order of an archived notice stays stable.
 */
@Entity
@Table(name = "announcements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "author_employee_id", nullable = false, updatable = false)
    private long authorEmployeeId;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false, columnDefinition = "text")
    private String body;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private NoticeTarget target;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private NoticeStatus status;
    @Column(name = "is_pinned", nullable = false)
    private boolean pinned;
    @Column(name = "published_at")
    private Instant publishedAt;
    // Both timestamps are DB-maintained (DEFAULT / ON UPDATE CURRENT_TIMESTAMP(6)).
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Generated(event = {EventType.INSERT, EventType.UPDATE}) @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
    /** Exposed to clients and checked on every notice command. */
    @Version
    private long version;
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "announcement_branches", joinColumns = @JoinColumn(name = "announcement_id"))
    @Column(name = "branch_id", nullable = false)
    private Set<Long> branchIds = new HashSet<>();

    public Notice(long author, String title, String body, NoticeTarget target, Set<Long> branchIds, boolean pinned,
            NoticeStatus status, Instant publishedAt) {
        this.authorEmployeeId = author; this.title = title; this.body = body; this.target = target; this.pinned = pinned;
        this.status = status; this.publishedAt = publishedAt;
        this.branchIds.addAll(branchIds);
    }

    public void edit(String title, String body, boolean pinned) { this.title = title; this.body = body; this.pinned = pinned; }

    /** Replaces the whole target; the caller has already checked the actor may manage both the old and the new one. */
    public void retarget(NoticeTarget target, Set<Long> branchIds) {
        this.target = target;
        this.branchIds.retainAll(branchIds);
        this.branchIds.addAll(branchIds);
    }

    public void publish(Instant at) { this.status = NoticeStatus.PUBLISHED; this.publishedAt = at; }

    public void archive() { this.status = NoticeStatus.ARCHIVED; }
}
