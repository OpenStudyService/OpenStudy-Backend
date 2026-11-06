package com.openstudy.domainlens.api.retrieval;
import java.util.List;
/** Embeds only approved, sanitized text. Provider errors must not include request bodies. */
public interface EmbeddingGateway {
    String model();
    int dimensions();
    List<List<Double>> embed(List<String> texts);
    /** Provider-specific document encoding; legacy gateways keep their existing behavior. */
    default List<List<Double>> embedDocuments(List<String> texts) { return embed(texts); }
    /** Query and document prefixes may differ (for example E5 query:/passage:). */
    default List<Double> embedQuery(String text) {
        var vectors = embed(List.of(text));
        if (vectors.size() != 1) throw new IllegalStateException("Embedding response count mismatch");
        return vectors.getFirst();
    }
}
