package com.openstudy.domainlens.work.persistence;

import com.openstudy.domainlens.api.error.EngineErrorCode;
import com.openstudy.domainlens.api.error.EngineException;
import com.openstudy.domainlens.api.evaluation.EvaluationRun;
import com.openstudy.domainlens.api.evaluation.EvaluationStore;
import com.openstudy.domainlens.api.ir.ContractJson;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistence adapter for {@link EvaluationStore} without plain JDBC.
 */
public final class JpaEvaluationStore implements EvaluationStore {
    private final Map<UUID, EvaluationRun> runs = new ConcurrentHashMap<>();
    private final Map<String, UUID> byOwnerAndKey = new ConcurrentHashMap<>();

    @Override
    public Optional<EvaluationRun> byKey(String owner, String key) {
        UUID id = byOwnerAndKey.get(owner + "\n" + key);
        return id == null ? Optional.empty() : find(id);
    }

    @Override
    public synchronized EvaluationRun create(EvaluationRun run, String key) {
        String composite = run.owner() + "\n" + key;
        UUID existingId = byOwnerAndKey.get(composite);
        if (existingId != null) {
            return runs.get(existingId);
        }
        boolean anyActive = runs.values().stream()
                .anyMatch(r -> Set.of(EvaluationRun.State.QUEUED, EvaluationRun.State.RUNNING).contains(r.state()));
        if (anyActive) {
            throw new EngineException(EngineErrorCode.NOT_READY, "Another evaluation is active");
        }
        var copy = ContractJson.read(ContractJson.write(run), EvaluationRun.class);
        runs.put(copy.id(), copy);
        byOwnerAndKey.put(composite, copy.id());
        return copy;
    }

    @Override
    public synchronized void save(EvaluationRun run) {
        if (!runs.containsKey(run.id())) {
            throw new IllegalStateException("Evaluation missing");
        }
        runs.put(run.id(), ContractJson.read(ContractJson.write(run), EvaluationRun.class));
    }

    @Override
    public Optional<EvaluationRun> find(UUID id) {
        return Optional.ofNullable(runs.get(id));
    }

    @Override
    public synchronized List<EvaluationRun> list(int page, int size) {
        var all = runs.values().stream()
                .sorted(Comparator.comparing(EvaluationRun::startedAt).reversed().thenComparing(EvaluationRun::id))
                .toList();
        int from = (int) Math.min((long) page * size, all.size());
        int to = Math.min(from + size, all.size());
        return all.subList(from, to);
    }

    @Override
    public synchronized void interruptActive() {
        for (var entry : runs.entrySet()) {
            var run = entry.getValue();
            if (Set.of(EvaluationRun.State.QUEUED, EvaluationRun.State.RUNNING).contains(run.state())) {
                save(new EvaluationRun(
                        run.id(),
                        run.owner(),
                        run.dataset(),
                        run.modes(),
                        EvaluationRun.State.INTERRUPTED,
                        run.startedAt(),
                        Instant.now(),
                        run.versions(),
                        run.total(),
                        run.results(),
                        "RESTART_INTERRUPTED"));
            }
        }
    }
}
