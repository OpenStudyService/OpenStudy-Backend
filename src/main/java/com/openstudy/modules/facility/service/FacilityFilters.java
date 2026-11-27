package com.openstudy.modules.facility.service;

import com.openstudy.modules.facility.entity.FacilityStatus;
import com.openstudy.modules.facility.entity.FacilityWorkStatus;

/** Validated list and summary conditions. Shared so the two cannot accept different filters. */
public record FacilityFilters(
        Long type,
        Long assignee,
        FacilityWorkStatus workStatus,
        boolean inspectionDue,
        FacilityStatus status
) {

    public static FacilityFilters parse(
            String typeId,
            String assigneeId,
            String workStatus,
            String inspectionDue,
            String status
    ) {
        Long type = typeId == null ? null : FacilityInputs.parseId(typeId, "typeId");
        Long assignee = assigneeId == null ? null : FacilityInputs.parseId(assigneeId, "assigneeId");
        FacilityWorkStatus parsedWorkStatus = workStatus == null
                ? null
                : FacilityInputs.parseWorkStatus(workStatus, "workStatus");
        boolean due = FacilityInputs.parseFlag(inspectionDue, "inspectionDue");
        FacilityStatus parsedStatus = status == null ? null : FacilityInputs.parseStatus(status, "status");

        return new FacilityFilters(type, assignee, parsedWorkStatus, due, parsedStatus);
    }
}
