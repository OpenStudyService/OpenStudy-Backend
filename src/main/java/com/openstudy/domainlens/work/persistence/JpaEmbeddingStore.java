package com.openstudy.domainlens.work.persistence;

import com.openstudy.domainlens.api.retrieval.EmbeddingStore;
import com.openstudy.domainlens.catalog.SearchText;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistence adapter for {@link EmbeddingStore} without plain JDBC.
 */
public final class JpaEmbeddingStore implements EmbeddingStore {
    private final String namespace;
    private final Map<String, Vector> store = new ConcurrentHashMap<>();

    public JpaEmbeddingStore() {
        this("default");
    }

    public JpaEmbeddingStore(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("Embedding namespace required");
        }
        this.namespace = namespace;
    }

    private String partition(String key) {
        return SearchText.hash(namespace + "\n" + key);
    }

    @Override
    public Optional<Vector> find(String key) {
        var v = store.get(partition(key));
        return v == null ? Optional.empty() : Optional.of(new Vector(key, v.model(), v.dimensions(), v.values()));
    }

    @Override
    public synchronized void save(List<Vector> vectors) {
        for (var v : vectors) {
            store.putIfAbsent(partition(v.key()), v);
        }
    }
}
