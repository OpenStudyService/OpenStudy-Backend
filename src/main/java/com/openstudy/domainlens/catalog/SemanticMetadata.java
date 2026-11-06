package com.openstudy.domainlens.catalog;

import com.openstudy.domainlens.api.semantic.SemanticEntity;
import com.openstudy.domainlens.api.semantic.SemanticField;
import java.util.List;
import java.util.Set;

/** Consumer-authored labels and constraints, independent of physical JPA paths. */
public record SemanticMetadata(String name, String description, List<String> aliases,
        String domain, String role, String unit, Set<String> allowedValues) {
    public static final SemanticMetadata EMPTY = new SemanticMetadata("", "", List.of(), "", "", "", Set.of());

    public SemanticMetadata {
        aliases = List.copyOf(aliases);
        allowedValues = Set.copyOf(allowedValues);
    }

    public static SemanticMetadata from(SemanticEntity annotation) {
        return annotation == null ? EMPTY : new SemanticMetadata(annotation.name(), annotation.description(),
                List.of(annotation.aliases()), annotation.domain(), "", "", Set.of());
    }

    public static SemanticMetadata from(SemanticField annotation) {
        return annotation == null ? EMPTY : new SemanticMetadata(annotation.name(), annotation.description(),
                List.of(annotation.aliases()), "", annotation.role(), annotation.unit(), Set.of(annotation.allowedValues()));
    }
}
