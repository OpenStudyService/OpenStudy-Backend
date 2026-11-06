package com.openstudy.domainlens.api.spi;

import org.jspecify.annotations.NonNull;

/**
 * Consumer-provided metric implementation.
 * Grain, join paths and aggregation live here — not in LLM formulas.
 */
public interface MetricImpl {

    /** Stable metric implementation id (matches SemanticMetric.implementationRef). */
    @NonNull String id();

    /** Implementation version. */
    @NonNull String version();

    /** Grain at which this implementation is valid. */
    @NonNull String grain();
}
