package com.openstudy.modules.facility.service;

import com.openstudy.modules.facility.entity.FacilityStatus;
import com.openstudy.modules.facility.entity.FacilityWorkStatus;

/** Validated list and summary conditions. Shared so the two cannot accept different filters. */
public record FacilityFilters(Long type, Long assignee, FacilityWorkStatus workStatus, boolean inspectionDue, FacilityStatus status) {
    public static FacilityFilters parse(String typeId, String assigneeId, String workStatus, String inspectionDue, String status) {
        return new FacilityFilters(typeId == null ? null : FacilityInputs.id(typeId, "typeId"),
                assigneeId == null ? null : FacilityInputs.id(assigneeId, "assigneeId"),
                workStatus == null ? null : FacilityInputs.workStatus(workStatus, "workStatus"),
                FacilityInputs.flag(inspectionDue, "inspectionDue"),
                status == null ? null : FacilityInputs.status(status, "status"));
    }
}
