package com.openstudy.domainlens.api.spi;

import java.util.Set;
import org.jspecify.annotations.NonNull;

/** Request-scoped identity supplied by the consumer; never inferred from model output. */
public interface UserContext {
    /** Consumers must report anonymous contexts as unauthenticated. */
    default boolean authenticated() { return true; }
    @NonNull String subjectId();
    @NonNull Set<String> roles();
    @NonNull PolicyBinding policyBinding();
}
