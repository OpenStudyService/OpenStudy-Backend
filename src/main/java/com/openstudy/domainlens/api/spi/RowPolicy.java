package com.openstudy.domainlens.api.spi;

import org.jspecify.annotations.NonNull;

/**
 * Row-scope policy that contributes predicates to executed SQL.
 * Concrete bindings come from {@link PolicyBinding}; the engine does not know role names.
 */
public interface RowPolicy {
    /** Explicit server-owned exemption for shared dimensions or globally authorized aggregates. */
    default boolean unrestricted(UserContext user,String sourceId,com.openstudy.domainlens.api.ir.QuerySpec query){return false;}


    /** A bound server predicate; never taken from model output. */
    default com.openstudy.domainlens.api.ir.Filter predicate(UserContext user,String sourceId) {
        throw new com.openstudy.domainlens.api.error.EngineException(
            com.openstudy.domainlens.api.error.EngineErrorCode.SCOPE_DENIED,"Row policy has no bound implementation");
    }
    /** Stable policy id. */
    @NonNull String id();
}
