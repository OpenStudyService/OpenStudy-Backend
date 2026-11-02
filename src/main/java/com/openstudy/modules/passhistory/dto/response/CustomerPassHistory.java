package com.openstudy.modules.passhistory.dto.response;

import java.time.Instant;
import java.util.List;

import com.openstudy.modules.customer.dto.response.CustomerSummary;

/** Contract schema {@code CustomerPassHistory}. */
public record CustomerPassHistory(CustomerSummary customer, Instant from, Instant to, List<PassHistoryEvent> events,
        List<PassLedgerCheck> passes) {}
