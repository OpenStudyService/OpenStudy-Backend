package com.openstudy.modules.chat.entity;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * A channel. {@code branchId} is null for a channel addressed to everyone. There is no delete: messages reference the
 * channel. The name, the description and the member list share {@code version}. The DM columns stay unmapped.
 */
@Entity
@Table(name = "conversations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Conversation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false)
    private ConversationKind kind;
    @Column(name = "branch_id", updatable = false)
    private Long branchId;
    @Column(length = 120)
    private String name;
    @Column(length = 500)
    private String description;
    @Column(name = "created_by", updatable = false)
    private Long createdBy;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Version
    private long version;

    public Conversation(ConversationKind kind, Long branchId, String name, String description, long createdBy) {
        this.kind = kind; this.branchId = branchId; this.name = name; this.description = description; this.createdBy = createdBy;
    }

    public void edit(String name, String description) { this.name = name; this.description = description; }
}
