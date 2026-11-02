package com.openstudy.modules.facility.dto.response;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Contract schema {@code FacilityListItem}. {@code status} is the facility's condition and {@code openWork.status} the
 * processing status; they are never merged. canEdit and canComplete only guide the screen; every command checks again.
 */
public record FacilityListItem(String id, String branchId, FacilityTypeView type, String name, String location, String status,
        int inspectionIntervalDays, LocalDate lastInspectedOn, LocalDate nextInspectionOn, boolean inspectionDue,
        FacilityOpenWork openWork, boolean canEdit, boolean canComplete, long version, Instant createdAt, Instant updatedAt) {}
