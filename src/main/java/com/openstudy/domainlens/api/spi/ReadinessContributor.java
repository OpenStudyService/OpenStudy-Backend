package com.openstudy.domainlens.api.spi;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Additional readiness check contributed by the consumer or engine subsystem.
 */
public interface ReadinessContributor {

    /** Stable consumer or engine failure code. */
    default String code() { return "NOT_READY"; }

    /** Short name used in diagnostics. */
    @NonNull String name();

    /** {@code true} when this check passes. */
    boolean ready();

    /** Optional detail when not ready. */
    @Nullable String detail();
}
