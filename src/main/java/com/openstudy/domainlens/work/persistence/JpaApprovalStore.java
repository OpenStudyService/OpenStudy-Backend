package com.openstudy.domainlens.work.persistence;

import com.openstudy.domainlens.api.error.EngineErrorCode;
import com.openstudy.domainlens.api.error.EngineException;
import com.openstudy.domainlens.api.ir.ContractJson;
import com.openstudy.domainlens.api.work.ApprovalStore;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Persistence adapter for {@link ApprovalStore} without plain JDBC.
 */
public final class JpaApprovalStore implements ApprovalStore {
    private final JpaArtifactStore artifactStore;
    private final Map<UUID, Approval> approvals = new ConcurrentHashMap<>();
    private final Map<String, UUID> byOwnerAndKey = new ConcurrentHashMap<>();

    public JpaApprovalStore() {
        this(null);
    }

    public JpaApprovalStore(JpaArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    @Override
    public synchronized Approval create(Approval approval) {
        String composite = approval.owner() + "\n" + approval.key();
        UUID existingId = byOwnerAndKey.get(composite);
        if (existingId != null) {
            var saved = approvals.get(existingId);
            if (!saved.hash().equals(approval.hash())
                    || !saved.executionId().equals(approval.executionId())
                    || !saved.datasetId().equals(approval.datasetId())
                    || !saved.componentId().equals(approval.componentId())) {
                throw new EngineException(EngineErrorCode.VALIDATION_ERROR, "Approval key already used");
            }
            return saved;
        }
        Approval copy = ContractJson.read(ContractJson.write(approval), Approval.class);
        approvals.put(copy.id(), copy);
        byOwnerAndKey.put(composite, copy.id());
        return copy;
    }

    @Override
    public Optional<Approval> find(UUID id, String owner) {
        var found = approvals.get(id);
        if (found == null || !found.owner().equals(owner)) return Optional.empty();
        return Optional.of(found);
    }

    @Override
    public List<Approval> list(String owner) {
        return approvals.values().stream()
                .filter(a -> a.owner().equals(owner))
                .sorted(Comparator.comparing(Approval::createdAt).reversed().thenComparing(Approval::id))
                .limit(100)
                .toList();
    }

    @Override
    public synchronized Approval update(UUID id, String owner, UnaryOperator<Approval> transition) {
        var saved = find(id, owner).orElseThrow(() -> new EngineException(EngineErrorCode.NOT_FOUND, "Approval not found"));
        var next = transition.apply(saved);
        if (!next.equals(saved)) {
            if (!next.equals(saved.state(next.state()))) {
                throw new IllegalArgumentException("Only approval state may change");
            }
            approvals.put(id, ContractJson.read(ContractJson.write(next), Approval.class));
        }
        if (saved.state() == State.PENDING && next.state() == State.APPROVED && artifactStore != null) {
            artifactStore.enqueue(next.id(), next.executionId(), next.owner());
        }
        return next;
    }
}
