package com.openstudy.domainlens.api.spi;

import org.jspecify.annotations.NonNull;

/**
 * Allowlist for interactive actions such as page, sort, drill, and csv export.
 */
public interface ActionAllowlist {

    default boolean allows(UserContext user,String actionId) { return allows(actionId); }

    /** Whether the named action is permitted for the current principal. */
    boolean allows(@NonNull String actionId);
}
