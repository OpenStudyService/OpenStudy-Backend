package com.openstudy.modules.invitation.service;

import java.time.Duration;

/** Bounded technical worker settings; manual reissue starts a new automatic attempt budget. */
public record InvitationDeliverySettings(int batchSize, Duration leaseDuration, Duration retryDelay, int maxAttempts) {

    private static final int MAX_BATCH_SIZE = 100;
    private static final int MAX_ATTEMPTS = 20;
    private static final Duration MIN_LEASE = Duration.ofSeconds(1);
    private static final Duration MAX_LEASE = Duration.ofMinutes(10);
    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(1);

    public InvitationDeliverySettings {
        boolean validCounts = batchSize >= 1
                && batchSize <= MAX_BATCH_SIZE
                && maxAttempts >= 1
                && maxAttempts <= MAX_ATTEMPTS;
        boolean validLease = leaseDuration != null
                && leaseDuration.compareTo(MIN_LEASE) >= 0
                && leaseDuration.compareTo(MAX_LEASE) <= 0;
        boolean validRetryDelay = retryDelay != null
                && !retryDelay.isNegative()
                && retryDelay.compareTo(MAX_RETRY_DELAY) <= 0;

        if (!validCounts || !validLease || !validRetryDelay) {
            throw new IllegalArgumentException("Invalid invitation delivery worker settings");
        }
    }
}
