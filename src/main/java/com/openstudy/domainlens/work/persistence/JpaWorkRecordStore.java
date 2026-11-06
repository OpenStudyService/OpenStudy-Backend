package com.openstudy.domainlens.work.persistence;

import com.openstudy.domainlens.api.work.WorkRecordStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistence adapter for {@link WorkRecordStore} without plain JDBC.
 */
public final class JpaWorkRecordStore implements WorkRecordStore {
    private final Map<UUID, Record> records = new ConcurrentHashMap<>();

    @Override
    public List<Record> list(String owner) {
        return records.values().stream()
                .filter(r -> r.owner().equals(owner))
                .sorted(Comparator.comparing(Record::createdAt).reversed().thenComparing(Record::id))
                .toList();
    }

    @Override
    public Optional<Record> find(UUID id, String owner) {
        var record = records.get(id);
        if (record == null || !record.owner().equals(owner)) return Optional.empty();
        return Optional.of(record);
    }

    public synchronized UUID recordLink(
            UUID existingRecordId,
            String owner,
            String title,
            Instant at,
            Link link) {
        UUID id = existingRecordId != null ? existingRecordId : UUID.randomUUID();
        var current = records.get(id);
        if (current == null) {
            records.put(id, new Record(id, owner, title, at, List.of(link)));
        } else {
            var links = new ArrayList<>(current.links());
            links.add(link);
            records.put(id, new Record(current.id(), current.owner(), current.title(), current.createdAt(), List.copyOf(links)));
        }
        return id;
    }

    public synchronized Optional<UUID> findRecordIdByReference(
            UUID referenceExecutionId,
            UUID referenceRequestId,
            String owner,
            UUID conversationId,
            String permissionHash) {
        for (var record : records.values()) {
            if (!record.owner().equals(owner)) continue;
            for (var link : record.links()) {
                boolean match = referenceExecutionId != null
                        ? referenceExecutionId.equals(link.executionId())
                        : referenceRequestId != null && referenceRequestId.equals(link.requestId());
                if (match && link.conversationId().equals(conversationId) && permissionHash.equals(link.permissionHash())) {
                    return Optional.of(record.id());
                }
            }
        }
        return Optional.empty();
    }

    public synchronized void updateLink(UUID requestId, String state, UUID executionId, String reason) {
        for (var entry : records.entrySet()) {
            var record = entry.getValue();
            boolean changed = false;
            var updatedLinks = new ArrayList<Link>();
            for (var link : record.links()) {
                if (link.requestId().equals(requestId)) {
                    updatedLinks.add(new Link(
                            link.requestId(),
                            link.conversationId(),
                            link.messageId(),
                            link.assistantMessageId(),
                            executionId != null ? executionId : link.executionId(),
                            state,
                            reason != null ? reason : link.reason(),
                            link.permissionHash(),
                            link.at()));
                    changed = true;
                } else {
                    updatedLinks.add(link);
                }
            }
            if (changed) {
                records.put(entry.getKey(), new Record(record.id(), record.owner(), record.title(), record.createdAt(), List.copyOf(updatedLinks)));
            }
        }
    }

    public synchronized Optional<Link> findLinkByRequest(UUID requestId) {
        for (var record : records.values()) {
            for (var link : record.links()) {
                if (link.requestId().equals(requestId)) return Optional.of(link);
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<UUID> findRequestByExecution(UUID executionId, String owner) {
        for (var record : records.values()) {
            if (!record.owner().equals(owner)) continue;
            for (var link : record.links()) {
                if (executionId.equals(link.executionId())) return Optional.of(link.requestId());
            }
        }
        return Optional.empty();
    }
}
