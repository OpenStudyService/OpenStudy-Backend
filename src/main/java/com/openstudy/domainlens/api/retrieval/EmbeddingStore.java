package com.openstudy.domainlens.api.retrieval;
import java.util.*;
public interface EmbeddingStore {
    record Vector(String key, String model, int dimensions, List<Double> values) {
        public Vector { values=List.copyOf(values); if(values.size()!=dimensions || values.stream().anyMatch(v->!Double.isFinite(v)) || values.stream().allMatch(v->v==0)) throw new IllegalArgumentException("Invalid embedding"); }
    }
    Optional<Vector> find(String key);
    /** Atomic batch. Failed/partial responses are never persisted. */
    void save(List<Vector> vectors);
}
