package com.openstudy.modules.facility.entity;

/**
 * facility_works.status. Only OPEN and IN_PROGRESS are visible in this contract ("대기", "진행 중"); CANCELLED never
 * occurs here because there is no cancel operation.
 */
public enum FacilityWorkStatus {
    OPEN("대기"), IN_PROGRESS("진행 중"), DONE("완료"), CANCELLED("취소");

    private final String label;
    FacilityWorkStatus(String label) { this.label = label; }
    public String label() { return label; }

    /** The two statuses of a work that is still open; the label is the contract's processing status. */
    public static FacilityWorkStatus ofOpenLabel(String label) {
        for (var status : new FacilityWorkStatus[] {OPEN, IN_PROGRESS}) if (status.label.equals(label)) return status;
        return null;
    }
}
