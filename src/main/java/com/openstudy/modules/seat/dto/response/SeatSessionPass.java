package com.openstudy.modules.seat.dto.response;

/** Contract schema {@code SeatSessionPass}: keeps the purchase branch apart from the session's use branch (FR-SEAT-18). */
public record SeatSessionPass(String id, String kind, String productName, String purchaseBranchId) {}
