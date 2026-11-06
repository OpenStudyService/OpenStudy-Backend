package com.openstudy.domainlens.api.ir;

import org.jspecify.annotations.NonNull;

/**
 * A single clarification choice shown to the user (e.g. UI chip).
 *
 * @param id    stable option id
 * @param label user-visible label
 */
public record ClarificationOption(
        @NonNull String id,
        @NonNull String label
) {
}
