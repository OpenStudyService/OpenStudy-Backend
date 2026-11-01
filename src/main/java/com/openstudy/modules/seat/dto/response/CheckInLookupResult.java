package com.openstudy.modules.seat.dto.response;

import java.util.List;

/** Contract schema {@code CheckInLookupResult}: at most ten customers by member number, no paging. */
public record CheckInLookupResult(List<CheckInCandidate> items) {}
