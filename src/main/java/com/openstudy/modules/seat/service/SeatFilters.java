package com.openstudy.modules.seat.service;

import com.openstudy.modules.seat.entity.SeatDisplayStatus;
import com.openstudy.modules.seat.entity.SeatKind;

/** Parsed list filters; a null member means no restriction. */
public record SeatFilters(String zone, SeatKind kind, SeatDisplayStatus status) {

    public static SeatFilters parse(String zone, String kind, String status) {
        String parsedZone = SeatInputs.parseZone(zone).orElse(null);
        SeatKind parsedKind = kind == null ? null : SeatInputs.parseKind(kind, "kind");
        SeatDisplayStatus parsedStatus = status == null ? null : SeatInputs.parseDisplayStatus(status, "status");

        return new SeatFilters(parsedZone, parsedKind, parsedStatus);
    }
}
