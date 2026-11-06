package com.openstudy.domainlens.api.retrieval;
import java.util.Set;
public record SearchDocument(String id, String text, Set<String> references) {
    public SearchDocument { references=Set.copyOf(references); if(id.isBlank() || text.isBlank()) throw new IllegalArgumentException("Empty search document"); }
}
