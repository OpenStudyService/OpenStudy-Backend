package com.openstudy.modules.passhistory.dto.response;

import java.time.Instant;

import com.openstudy.modules.seat.dto.response.ActorSummary;

/**
 * Contract schema {@code PassHistoryEvent}; no payment amount (NFR-SEC-06). The deltas are ledger deltas, so purchases and
 * refunds carry zero. A stay at a branch outside the request's scope shows branch, time and deltas only.
 */
public record PassHistoryEvent(Instant at, String type, String passId, String productName, long deltaMinutes, long deltaKrw,
        Long usedMinutes, String branchId, String paymentId, String seatSessionId, String reason, ActorSummary actor) {}
