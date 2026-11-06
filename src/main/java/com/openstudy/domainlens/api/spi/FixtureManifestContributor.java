package com.openstudy.domainlens.api.spi;

import org.jspecify.annotations.NonNull;

/**
 * Supplies fixture digest (and related identity) for readiness / mismatch checks.
 */
public interface FixtureManifestContributor {

    /** Logical fixture id. */
    @NonNull String fixtureId();

    /** Content digest (hash) of the pinned fixture. */
    @NonNull String contentDigest();
}
