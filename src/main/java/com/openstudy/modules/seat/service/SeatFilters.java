package com.openstudy.modules.seat.service;

import com.openstudy.modules.seat.entity.SeatDisplayStatus;
import com.openstudy.modules.seat.entity.SeatKind;

/** Parsed list filters; a null member means no restriction. */
public record SeatFilters(String zone, SeatKind kind, SeatDisplayStatus status) {
    public static SeatFilters parse(String zone, String kind, String status) {
        return new SeatFilters(SeatInputs.zone(zone).orElse(null), kind == null ? null : SeatInputs.kind(kind, "kind"),
                status == null ? null : SeatInputs.display(status, "status"));
    }
}
