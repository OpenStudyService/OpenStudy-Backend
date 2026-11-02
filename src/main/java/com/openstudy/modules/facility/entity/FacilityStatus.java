package com.openstudy.modules.facility.entity;

/** Facility condition (FR-FAC-01). Kept apart from the processing status of the open inspection work (M7-D03). */
public enum FacilityStatus {
    NORMAL("정상"), CHECK_NEEDED("점검 필요"), BROKEN("고장"), OUT_OF_SERVICE("사용 중지");

    private final String label;
    FacilityStatus(String label) { this.label = label; }
    public String label() { return label; }

    public static FacilityStatus ofLabel(String label) {
        for (var status : values()) if (status.label.equals(label)) return status;
        return null;
    }
}
