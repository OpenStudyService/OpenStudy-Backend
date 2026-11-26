package com.openstudy.modules.authorization.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Read-only permission code dictionary; rows come from migrations and seeds only. */
@Entity
@Table(name = "permission_definitions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PermissionDefinition {

    @Id
    private Long id;

    @Column(nullable = false, length = 80, insertable = false, updatable = false)
    private String code;

    @Column(nullable = false, length = 255, insertable = false, updatable = false)
    private String description;

    @Column(name = "is_admin_only", nullable = false, insertable = false, updatable = false)
    private boolean adminOnly;
}
