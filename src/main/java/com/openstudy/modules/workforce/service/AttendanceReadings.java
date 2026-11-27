package com.openstudy.modules.workforce.service;

import java.time.Duration;
import java.util.List;

import com.openstudy.modules.workforce.entity.AttendanceBreak;
import com.openstudy.modules.workforce.entity.AttendanceRecord;

/**
 * The one reading of an attendance row, for every response that shows one: its state and its worked minutes (M8-D07).
 * Nothing here is stored; a list, a detail, the weekly schedule and a shift's linked attendance compute the same way.
 */
final class AttendanceReadings {

    private static final String LEFT = "퇴근";
    private static final String ON_BREAK = "휴게 중";
    private static final String WORKING = "근무 중";
    private static final int SECONDS_PER_MINUTE = 60;

    private AttendanceReadings() {
    }

    /** WORKING with an open break is "휴게 중"; the stored status alone cannot tell. */
    static String toStatus(AttendanceRecord record, List<AttendanceBreak> breaks) {
        if (record.getStatus() == AttendanceRecord.Status.LEFT) {
            return LEFT;
        }

        boolean onBreak = breaks.stream()
                .anyMatch(item -> item.getEndedAt() == null);

        return onBreak ? ON_BREAK : WORKING;
    }

    /**
     * (clock-out − clock-in − Σ breaks) in seconds, then whole minutes once, never below zero. Null before clock-out: a
     * running value would differ between two reads of the same record.
     */
    static Long calculateWorkedMinutes(AttendanceRecord record, List<AttendanceBreak> breaks) {
        if (record.getStatus() != AttendanceRecord.Status.LEFT || record.getClockOutAt() == null) {
            return null;
        }

        long seconds = Duration.between(record.getClockInAt(), record.getClockOutAt()).getSeconds();

        for (AttendanceBreak item : breaks) {
            if (item.getEndedAt() != null) {
                seconds -= Duration.between(item.getStartedAt(), item.getEndedAt()).getSeconds();
            }
        }

        return Math.max(0, Math.floorDiv(seconds, SECONDS_PER_MINUTE));
    }
}
