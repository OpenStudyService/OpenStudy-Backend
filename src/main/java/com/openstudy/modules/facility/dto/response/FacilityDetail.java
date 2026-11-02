package com.openstudy.modules.facility.dto.response;

import java.time.Instant;
import java.time.LocalDate;

/** Contract schema {@code Facility}: the list item plus the description and the last inspection result. */
public record FacilityDetail(String id, String branchId, FacilityTypeView type, String name, String location, String status,
        int inspectionIntervalDays, LocalDate lastInspectedOn, LocalDate nextInspectionOn, boolean inspectionDue,
        FacilityOpenWork openWork, boolean canEdit, boolean canComplete, long version, Instant createdAt, Instant updatedAt,
        String description, FacilityLastInspection lastInspection) {}
