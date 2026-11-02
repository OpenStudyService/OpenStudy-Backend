package com.openstudy.modules.facility.dto.response;

import java.time.LocalDate;

/** Contract schema {@code FacilityLastInspection}. */
public record FacilityLastInspection(LocalDate completedOn, String result, ActorSummary completedBy) {}
