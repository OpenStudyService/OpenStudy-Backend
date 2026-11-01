package com.openstudy.modules.seat.dto.response;

import java.time.Instant;
import java.util.List;

import com.openstudy.modules.customer.dto.response.CustomerSummary;
import com.openstudy.modules.ticket.dto.UsablePass;

/** Contract schema {@code CheckInCandidate}: only what check-in needs (M5-D05). No contact, memo or payment data. */
public record CheckInCandidate(CustomerSummary customer, String status, ActiveSession activeSession, List<UsablePass> usablePasses) {
    /** Where the customer is checked in right now, at any branch. */
    public record ActiveSession(String id, String branchId, Instant checkedInAt) {}
}
