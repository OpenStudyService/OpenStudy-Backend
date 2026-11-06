package com.openstudy.domainlens.api.spi;

import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * Injects the current principal's role key and opaque scope values into the engine.
 * The engine does not interpret role names or domain-specific scope ids.
 */
public interface PolicyBinding {

    /** Opaque role key supplied by the consumer. */
    @NonNull String roleKey();

    /** Opaque scope map (e.g. partition keys); values are consumer-defined. */
    @NonNull Map<String, Object> scope();
}
