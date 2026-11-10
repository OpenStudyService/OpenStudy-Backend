package com.openstudy.modules.customer.dto.response;

import java.time.Instant;

/** Contract schema {@code CustomerMergeRecord}: one absorbed customer of one merge (M13-D03). */
public record CustomerMergeRecord(String id, CustomerSummary target, CustomerSummary absorbed, CustomerSummary via,
        ActorSummary mergedBy, Instant mergedAt, String reason) {

    /** Contract schema {@code ActorSummary}. */
    public record ActorSummary(String id, String name) {}
}
