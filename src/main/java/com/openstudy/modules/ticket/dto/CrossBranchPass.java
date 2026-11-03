package com.openstudy.modules.ticket.dto;

import java.time.Instant;

/** Contract schema {@code CrossBranchPass}: no balance and no purchase branch (POL-01, M6-D06). */
public record CrossBranchPass(String kind, String productName, String status, Instant expiresAt) {}
