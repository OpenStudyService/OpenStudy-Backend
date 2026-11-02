package com.openstudy.modules.facility.dto.response;

import java.time.Instant;

/** Contract schema {@code FacilityOpenWork}: the one open inspection work, the source of assignee and processing status. */
public record FacilityOpenWork(String id, String status, ActorSummary assignee, Instant openedAt) {}
