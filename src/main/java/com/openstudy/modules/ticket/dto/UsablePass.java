package com.openstudy.modules.ticket.dto;

import java.time.Instant;

/** Contract schema {@code UsablePass}: a pass that can check in right now, at any active branch (M5-D01). */
public record UsablePass(String id, String kind, String productName, Long remainingMinutes, Long remainingCreditKrw,
        Instant expiresAt, String purchaseBranchId) {}
