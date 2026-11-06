package com.openstudy.domainlens.api.spi;

import org.jspecify.annotations.NonNull;

/**
 * Field-level policy controlling SELECT / filter / group / sort / CSV columns.
 */
public interface FieldPolicy {

    default boolean allows(UserContext user,String fieldId,String operation) { return allows(fieldId,operation); }

    /** Stable policy id. */
    @NonNull String id();

    /**
     * Whether {@code fieldId} may participate in the given operation
     * (consumer-defined op vocabulary, e.g. select, filter, csv).
     */
    boolean allows(@NonNull String fieldId, @NonNull String operation);
}
