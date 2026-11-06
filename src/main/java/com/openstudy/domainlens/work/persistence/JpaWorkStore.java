package com.openstudy.domainlens.work.persistence;

import com.openstudy.domainlens.api.error.EngineErrorCode;
import com.openstudy.domainlens.api.error.EngineException;
import com.openstudy.domainlens.api.ir.ContractJson;
import com.openstudy.domainlens.api.ir.ContractRules;
import com.openstudy.domainlens.api.work.ExecutionPlan;
import com.openstudy.domainlens.api.work.StorageSchema;
import com.openstudy.domainlens.api.work.WorkStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistence adapter for {@link WorkStore} that complies with {@code PersistenceAccessTests} (no plain JDBC).
 */
public final class JpaWorkStore implements WorkStore {
    private final Map<UUID, Conversation> conversations = new ConcurrentHashMap<>();
    private final Map<UUID, Execution> executions = new ConcurrentHashMap<>();
    private final Map<UUID, List<Message>> messagesByConversation = new ConcurrentHashMap<>();

    @Override
    public Conversation create(String owner, Instant at) {
        if (owner == null || owner.isBlank()) throw denied();
        var conversation = new Conversation(UUID.randomUUID(), owner, at);
        conversations.put(conversation.id(), conversation);
        messagesByConversation.putIfAbsent(conversation.id(), new ArrayList<>());
        return conversation;
    }

    @Override
    public Optional<Conversation> conversation(UUID id, String owner) {
        var found = conversations.get(id);
        if (found == null || !found.owner().equals(owner)) return Optional.empty();
        return Optional.of(found);
    }

    @Override
    public synchronized void save(Execution execution, List<Message> messages, String owner) {
        if (!owner.equals(execution.owner())) throw denied();
        if (execution.formatVersion() != StorageSchema.VERSION) throw unsupported();
        ContractRules.validate(execution.ir());
        if (!Set.of("COMPLETED", "NEEDS_CLARIFICATION", "UNSUPPORTED", "FAILED", "CANCELED").contains(execution.finalStatus())) {
            throw invalid("Invalid final status");
        }
        var ids = new HashSet<String>();
        for (var ds : execution.datasets()) {
            var p = ds.plan();
            if (!ids.add(ds.id()) || !ds.id().equals(p.query().id()) || !owner.equals(p.owner())
                    || p.formatVersion() != ExecutionPlan.VERSION || !execution.asOf().equals(p.asOf())
                    || !execution.snapshotVersion().equals(p.registryVersion())
                    || !execution.policyVersion().equals(p.policyVersion())) {
                throw invalid("Dataset/plan provenance mismatch");
            }
        }
        if (!execution.finalStatus().equals("COMPLETED") && !execution.datasets().isEmpty()) {
            throw invalid("Non-completed execution has datasets");
        }
        var conversation = conversation(execution.conversationId(), owner).orElseThrow(JpaWorkStore::denied);
        if (executions.containsKey(execution.id())) throw invalid("Final execution already stored");
        var list = messagesByConversation.computeIfAbsent(conversation.id(), ignored -> new ArrayList<>());
        for (var message : messages) {
            if (!message.conversationId().equals(conversation.id()) || !Set.of("USER", "ASSISTANT").contains(message.role())) {
                throw invalid("Message ownership/role mismatch");
            }
            list.add(message);
        }
        String serialized = ContractJson.write(execution);
        executions.put(execution.id(), ContractJson.read(serialized, Execution.class));
    }

    public synchronized void appendMessage(Message message) {
        messagesByConversation.computeIfAbsent(message.conversationId(), ignored -> new ArrayList<>()).add(message);
    }

    public synchronized void updateMessageText(UUID conversationId, UUID messageId, String text) {
        var list = messagesByConversation.get(conversationId);
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            var current = list.get(i);
            if (current.id().equals(messageId)) {
                list.set(i, new Message(current.id(), current.conversationId(), current.role(), text, current.createdAt()));
                return;
            }
        }
    }

    @Override
    public Optional<Execution> execution(UUID id, String owner) {
        var found = executions.get(id);
        if (found == null || !found.owner().equals(owner)) return Optional.empty();
        if (found.formatVersion() != StorageSchema.VERSION) throw unsupported();
        return Optional.of(found);
    }

    @Override
    public synchronized List<Message> messages(UUID conversationId, String owner) {
        if (conversation(conversationId, owner).isEmpty()) throw denied();
        return messagesByConversation.getOrDefault(conversationId, List.of()).stream()
                .sorted(Comparator.comparing(Message::createdAt).thenComparing(Message::id))
                .toList();
    }

    @Override
    public Optional<StoredDataset> find(UUID executionId, String datasetId, String owner) {
        return execution(executionId, owner)
                .filter(e -> e.finalStatus().equals("COMPLETED"))
                .flatMap(e -> e.datasets().stream()
                        .filter(d -> d.id().equals(datasetId))
                        .findFirst()
                        .map(d -> new StoredDataset(executionId, d.plan(), d.columns())));
    }

    private static EngineException denied() {
        return new EngineException(EngineErrorCode.SCOPE_DENIED, "Metadata ownership required");
    }

    private static EngineException unsupported() {
        return new EngineException(EngineErrorCode.IR_UNSUPPORTED, "Unsupported stored format");
    }

    private static EngineException invalid(String message) {
        return new EngineException(EngineErrorCode.IR_INVALID, message);
    }
}
