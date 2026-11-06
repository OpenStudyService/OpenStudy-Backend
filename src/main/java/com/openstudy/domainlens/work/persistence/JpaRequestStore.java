package com.openstudy.domainlens.work.persistence;

import com.openstudy.domainlens.api.error.EngineErrorCode;
import com.openstudy.domainlens.api.error.EngineException;
import com.openstudy.domainlens.api.ir.ContractJson;
import com.openstudy.domainlens.api.ir.OutcomeNotice;
import com.openstudy.domainlens.api.work.RequestStore;
import com.openstudy.domainlens.api.work.WorkRecordStore;
import com.openstudy.domainlens.api.work.WorkStore;
import com.openstudy.domainlens.catalog.SearchText;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistence adapter for {@link RequestStore} without plain JDBC.
 */
public final class JpaRequestStore implements RequestStore {
    private final JpaWorkStore workStore;
    private final JpaWorkRecordStore workRecordStore;
    private final Map<UUID, Request> requests = new ConcurrentHashMap<>();
    private final Map<String, UUID> byOwnerAndKey = new ConcurrentHashMap<>();
    private final Map<UUID, List<Event>> eventsByRequest = new ConcurrentHashMap<>();

    public JpaRequestStore() {
        this(null, null);
    }

    public JpaRequestStore(JpaWorkStore workStore, JpaWorkRecordStore workRecordStore) {
        this.workStore = workStore;
        this.workRecordStore = workRecordStore;
    }

    @Override
    public Accepted accept(UUID conversation, String owner, String key, String inputHash, Instant asOf, String snapshotVersion) {
        return accept(conversation, owner, key, inputHash, asOf, snapshotVersion, null);
    }

    @Override
    public synchronized Accepted accept(
            UUID conversation,
            String owner,
            String key,
            String hash,
            Instant asOf,
            String version,
            Context context) {
        if (key == null || key.isBlank() || key.length() > 128 || hash == null || !hash.matches("[a-f0-9]{64}")) {
            throw new EngineException(EngineErrorCode.VALIDATION_ERROR, "Invalid idempotency key or fingerprint");
        }
        if (workStore != null && workStore.conversation(conversation, owner).isEmpty()) {
            throw missing();
        }
        String compositeKey = owner + "\n" + key;
        UUID existingId = byOwnerAndKey.get(compositeKey);
        if (existingId != null) {
            var existing = requests.get(existingId);
            if (!existing.conversationId().equals(conversation) || !existing.inputHash().equals(hash)) {
                throw new EngineException(EngineErrorCode.VALIDATION_ERROR, "Idempotency key already used for a different request");
            }
            return new Accepted(existing, false);
        }
        UUID id = UUID.randomUUID();
        var request = new Request(id, conversation, owner, key, hash, asOf, version, "accepted");
        requests.put(id, request);
        byOwnerAndKey.put(compositeKey, id);
        eventsByRequest.put(id, new ArrayList<>());
        if (context != null) {
            recordContext(request, context);
        }
        appendInternal(request, "accepted", Map.of("asOf", asOf.toString(), "snapshotVersion", version));
        return new Accepted(requests.get(id), true);
    }

    private void recordContext(Request request, Context context) {
        if (workRecordStore == null) return;
        UUID recordId = null;
        if (context.referenceExecutionId() != null || context.referenceRequestId() != null) {
            var found = workRecordStore.findRecordIdByReference(
                    context.referenceExecutionId(),
                    context.referenceRequestId(),
                    request.owner(),
                    request.conversationId(),
                    context.permissionHash());
            if (found.isPresent()) {
                recordId = found.get();
            } else if (context.referenceRequestId() != null) {
                throw missing();
            }
        }
        Instant at = Instant.now();
        String question = SearchText.sanitize(context.question());
        UUID messageId = UUID.randomUUID();
        UUID assistantId = UUID.randomUUID();
        if (workStore != null) {
            workStore.appendMessage(new WorkStore.Message(messageId, request.conversationId(), "USER", question, at));
            workStore.appendMessage(new WorkStore.Message(assistantId, request.conversationId(), "ASSISTANT", "accepted", at.plusNanos(1000)));
        }
        var link = new WorkRecordStore.Link(
                request.id(),
                request.conversationId(),
                messageId,
                assistantId,
                null,
                "accepted",
                null,
                context.permissionHash(),
                at);
        workRecordStore.recordLink(recordId, request.owner(), question, at, link);
    }

    @Override
    public Optional<Request> find(UUID id, String owner) {
        var req = requests.get(id);
        if (req == null || !req.owner().equals(owner)) return Optional.empty();
        return Optional.of(req);
    }

    @Override
    public Optional<Request> forExecution(UUID execution, String owner) {
        if (workRecordStore == null) return Optional.empty();
        return workRecordStore.findRequestByExecution(execution, owner).flatMap(id -> find(id, owner));
    }

    @Override
    public synchronized List<Event> events(UUID id, String owner, long after) {
        if (after < 0) throw new EngineException(EngineErrorCode.VALIDATION_ERROR, "Invalid event cursor");
        find(id, owner).orElseThrow(JpaRequestStore::missing);
        return eventsByRequest.getOrDefault(id, List.of()).stream()
                .filter(e -> e.id() > after)
                .toList();
    }

    @Override
    public synchronized boolean append(UUID id, String owner, String type, Map<String, Object> data) {
        var req = find(id, owner).orElseThrow(JpaRequestStore::missing);
        return appendInternal(req, type, data);
    }

    private boolean appendInternal(Request request, String type, Map<String, Object> data) {
        if (RequestStore.terminal(request.state())) return false;
        if (!Set.of("accepted", "retrieving", "generating", "validating", "executing", "completed", "clarification", "failed", "canceled").contains(type)) {
            throw new IllegalArgumentException("Unknown request event");
        }
        if (!Set.of("asOf", "snapshotVersion", "executionId", "code", "retryable", "usage", "notice").containsAll(data.keySet())) {
            throw new IllegalArgumentException("Invalid event metadata");
        }
        if (data.containsKey("notice")) {
            if (!(data.get("code") instanceof String code) || !Set.of("failed", "canceled").contains(type)) {
                throw new IllegalArgumentException("Invalid notice event");
            }
            var notice = ContractJson.read(ContractJson.write(data.get("notice")), OutcomeNotice.class);
            if (!notice.equals(OutcomeNotice.forCode(code))) {
                throw new IllegalArgumentException("Notice must use server templates");
            }
        }
        var list = eventsByRequest.computeIfAbsent(request.id(), ignored -> new ArrayList<>());
        long nextId = list.size() + 1L;
        @SuppressWarnings("unchecked")
        Map<String, Object> safeData = ContractJson.read(ContractJson.write(data), Map.class);
        list.add(new Event(nextId, type, safeData, Instant.now()));
        var updated = new Request(
                request.id(),
                request.conversationId(),
                request.owner(),
                request.key(),
                request.inputHash(),
                request.asOf(),
                request.snapshotVersion(),
                type);
        requests.put(request.id(), updated);
        if (workRecordStore != null) {
            UUID executionId = data.get("executionId") == null ? null : UUID.fromString(data.get("executionId").toString());
            String code = (String) data.get("code");
            workRecordStore.updateLink(request.id(), type, executionId, code);
            if (RequestStore.terminal(type) && workStore != null) {
                workRecordStore.findLinkByRequest(request.id()).ifPresent(link ->
                        workStore.updateMessageText(link.conversationId(), link.assistantMessageId(), type));
            }
        }
        return true;
    }

    @Override
    public synchronized int interruptActive() {
        var activeList = requests.values().stream()
                .filter(r -> !RequestStore.terminal(r.state()))
                .toList();
        for (var req : activeList) {
            appendInternal(req, "failed", Map.of("code", "SERVER_INTERRUPTED", "retryable", true));
        }
        return activeList.size();
    }

    private static EngineException missing() {
        return new EngineException(EngineErrorCode.NOT_FOUND, "Request or conversation not found");
    }
}
