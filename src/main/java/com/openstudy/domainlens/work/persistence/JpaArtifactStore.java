package com.openstudy.domainlens.work.persistence;

import com.openstudy.domainlens.api.work.ArtifactStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistence adapter for {@link ArtifactStore} without plain JDBC.
 */
public final class JpaArtifactStore implements ArtifactStore {
    private final Map<UUID, Artifact> artifacts = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> byApproval = new ConcurrentHashMap<>();

    public synchronized Artifact enqueue(UUID approvalId, UUID executionId, String owner) {
        UUID existingId = byApproval.get(approvalId);
        if (existingId != null) return artifacts.get(existingId);
        UUID id = UUID.randomUUID();
        var artifact = new Artifact(
                id,
                approvalId,
                executionId,
                owner,
                "domainlens-" + approvalId + ".csv",
                State.QUEUED,
                0L,
                Instant.now(),
                null,
                null);
        artifacts.put(id, artifact);
        byApproval.put(approvalId, id);
        return artifact;
    }

    @Override
    public Optional<Artifact> find(UUID id, String owner) {
        var a = artifacts.get(id);
        if (a == null || !a.owner().equals(owner)) return Optional.empty();
        return Optional.of(a);
    }

    @Override
    public Optional<Artifact> forApproval(UUID approvalId, String owner) {
        UUID id = byApproval.get(approvalId);
        return id == null ? Optional.empty() : find(id, owner);
    }

    @Override
    public boolean retain(UUID id, Instant now) {
        var a = artifacts.get(id);
        if (a == null) return false;
        if (a.state() == State.QUEUED || a.state() == State.GENERATING) return true;
        return a.state() == State.READY && a.expiresAt() != null && a.expiresAt().isAfter(now);
    }

    @Override
    public synchronized boolean claim(UUID id, String owner) {
        var a = artifacts.get(id);
        if (a == null || !a.owner().equals(owner) || a.state() != State.QUEUED) return false;
        artifacts.put(id, new Artifact(a.id(), a.approvalId(), a.executionId(), a.owner(), a.name(), State.GENERATING, a.rows(), a.createdAt(), a.expiresAt(), a.errorCode()));
        return true;
    }

    @Override
    public synchronized void finish(UUID id, String owner, State state, long rows, Instant expiresAt, String code) {
        var a = artifacts.get(id);
        if (a == null || !a.owner().equals(owner) || a.state() != State.GENERATING) return;
        artifacts.put(id, new Artifact(a.id(), a.approvalId(), a.executionId(), a.owner(), a.name(), state, rows, a.createdAt(), expiresAt, code));
    }

    @Override
    public synchronized void interruptActive() {
        for (var entry : artifacts.entrySet()) {
            var a = entry.getValue();
            if (Set.of(State.QUEUED, State.GENERATING).contains(a.state())) {
                artifacts.put(entry.getKey(), new Artifact(a.id(), a.approvalId(), a.executionId(), a.owner(), a.name(), State.FAILED, a.rows(), a.createdAt(), a.expiresAt(), "SERVER_INTERRUPTED"));
            }
        }
    }

    @Override
    public synchronized List<Artifact> expire(Instant now) {
        var expired = new ArrayList<Artifact>();
        for (var entry : artifacts.entrySet()) {
            var a = entry.getValue();
            if (a.state() == State.READY && a.expiresAt() != null && !a.expiresAt().isAfter(now)) {
                var next = new Artifact(a.id(), a.approvalId(), a.executionId(), a.owner(), a.name(), State.EXPIRED, a.rows(), a.createdAt(), a.expiresAt(), a.errorCode());
                artifacts.put(entry.getKey(), next);
                expired.add(next);
            }
        }
        return List.copyOf(expired);
    }
}
