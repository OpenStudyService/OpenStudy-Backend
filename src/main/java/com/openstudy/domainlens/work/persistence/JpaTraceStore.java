package com.openstudy.domainlens.work.persistence;

import com.openstudy.domainlens.api.ir.ContractJson;
import com.openstudy.domainlens.api.work.TraceStore;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistence adapter for {@link TraceStore} without plain JDBC.
 */
public final class JpaTraceStore implements TraceStore {
    private record Entry(UUID requestId, String owner, String state, Instant createdAt, Map<UUID, Span> spans) {}

    private final Map<UUID, Entry> traces = new ConcurrentHashMap<>();

    @Override
    public synchronized void begin(UUID request, String owner, Instant at) {
        traces.putIfAbsent(request, new Entry(request, owner, "running", at, new LinkedHashMap<>()));
    }

    @Override
    public synchronized void span(UUID request, Span span) {
        var entry = traces.get(request);
        if (entry == null) return;
        entry.spans().put(span.id(), ContractJson.read(ContractJson.write(span), Span.class));
    }

    @Override
    public synchronized void finish(UUID request, String state) {
        var entry = traces.get(request);
        if (entry == null) return;
        traces.put(request, new Entry(entry.requestId(), entry.owner(), state, entry.createdAt(), entry.spans()));
    }

    @Override
    public synchronized void recover() {
        for (var kv : traces.entrySet()) {
            var entry = kv.getValue();
            String nextState = "running".equals(entry.state()) ? "failed" : entry.state();
            for (var spanEntry : entry.spans().entrySet()) {
                var s = spanEntry.getValue();
                if ("RUNNING".equals(s.state())) {
                    entry.spans().put(spanEntry.getKey(), new Span(s.id(), s.name(), "FAILED", s.startedAt(), s.durationNanos(), "SERVER_INTERRUPTED", s.attributes()));
                }
            }
            traces.put(kv.getKey(), new Entry(entry.requestId(), entry.owner(), nextState, entry.createdAt(), entry.spans()));
        }
    }

    @Override
    public synchronized void purge(Instant before) {
        traces.entrySet().removeIf(e -> e.getValue().createdAt().isBefore(before));
    }

    @Override
    public synchronized List<Trace> list(int page, int size, Instant since) {
        var all = traces.values().stream()
                .filter(e -> !e.createdAt().isBefore(since))
                .sorted(Comparator.comparing(Entry::createdAt).reversed().thenComparing(Entry::requestId))
                .map(this::toTrace)
                .toList();
        int from = (int) Math.min((long) page * size, all.size());
        int to = Math.min(from + size, all.size());
        return all.subList(from, to);
    }

    @Override
    public synchronized Optional<Trace> find(UUID id, Instant since) {
        var entry = traces.get(id);
        if (entry == null || entry.createdAt().isBefore(since)) return Optional.empty();
        return Optional.of(toTrace(entry));
    }

    private Trace toTrace(Entry entry) {
        var orderedSpans = entry.spans().values().stream()
                .sorted(Comparator.comparing(Span::startedAt).thenComparing(Span::id))
                .toList();
        return new Trace(entry.requestId(), entry.owner(), entry.state(), entry.createdAt(), orderedSpans);
    }
}
