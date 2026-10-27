package com.openstudy.modules.invitation.service;

import java.time.Duration;

/** Bounded technical worker settings; manual reissue starts a new automatic attempt budget. */
public record InvitationDeliverySettings(int batchSize, Duration leaseDuration, Duration retryDelay, int maxAttempts) {
    public InvitationDeliverySettings {
        if (batchSize < 1 || batchSize > 100 || maxAttempts < 1 || maxAttempts > 20
                || leaseDuration == null || leaseDuration.compareTo(Duration.ofSeconds(1)) < 0
                || leaseDuration.compareTo(Duration.ofMinutes(10)) > 0
                || retryDelay == null || retryDelay.isNegative() || retryDelay.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Invalid invitation delivery worker settings");
        }
    }
}
