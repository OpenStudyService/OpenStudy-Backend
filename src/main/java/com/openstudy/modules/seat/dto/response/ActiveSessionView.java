package com.openstudy.modules.seat.dto.response;

import java.time.Instant;

import com.openstudy.modules.customer.dto.response.CustomerSummary;

/** Contract schema {@code ActiveSessionSummary}: who is using the seat now, with the minimum customer data (M5-D06). */
public record ActiveSessionView(String id, CustomerSummary customer, Instant checkedInAt) {}
