package com.openstudy.common.idempotency;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/** A completed create command, replayed for the same actor/operation/scope/key (idempotency_key is binary-collated). */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "actor_employee_id", nullable = false, updatable = false)
    private Long actorEmployeeId;
    @Column(nullable = false, length = 120, updatable = false)
    private String operation;
    @Column(name = "scope_key", nullable = false, length = 160, updatable = false)
    private String scopeKey;
    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;
    @JdbcTypeCode(SqlTypes.BINARY) @Column(name = "request_hash", nullable = false, length = 32, updatable = false)
    private byte[] requestHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private IdempotencyState state;
    @JdbcTypeCode(SqlTypes.SMALLINT) @Column(name = "http_status")
    private Integer httpStatus;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "response_json")
    private String responseJson;
    @Generated(event = EventType.INSERT) @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyRecord() {}

    IdempotencyRecord(long actor, String operation, String scopeKey, String key, byte[] requestHash, int httpStatus,
            String responseJson, Instant expiresAt) {
        this.actorEmployeeId = actor; this.operation = operation; this.scopeKey = scopeKey; this.idempotencyKey = key;
        this.requestHash = requestHash; this.state = IdempotencyState.COMPLETED; this.httpStatus = httpStatus;
        this.responseJson = responseJson; this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public byte[] getRequestHash() { return requestHash.clone(); }
    public IdempotencyState getState() { return state; }
    public Integer getHttpStatus() { return httpStatus; }
    public String getResponseJson() { return responseJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
