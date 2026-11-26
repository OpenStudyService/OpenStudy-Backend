package com.openstudy.modules.branch.service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.openstudy.common.web.ApiVersion;
import com.openstudy.modules.branch.dto.Branch;
import com.openstudy.modules.branch.dto.Branch.WeeklyHours;
import com.openstudy.modules.branch.entity.BranchStatus;

/** Entity → API DTO mapping for branch management. Entities never leave the service layer (M1-04). */
final class BranchViews {

    static final String ACTIVE_LABEL = "운영 중";
    static final String SUSPENDED_LABEL = "운영 중지";

    private static final int MINUTES_PER_DAY = 1440;
    private static final int MINUTES_PER_HOUR = 60;

    private BranchViews() {
    }

    static Branch toDto(com.openstudy.modules.branch.entity.Branch branch) {
        String id = Long.toString(branch.getId());
        List<WeeklyHours> hours = toWeeklyHours(branch);
        String display = hours.stream()
                .map(BranchViews::toDisplay)
                .collect(Collectors.joining(", "));

        return new Branch(
                id,
                ApiVersion.fromDatabase(branch.getVersion()),
                branch.getCreatedAt().toString(),
                branch.getUpdatedAt().toString(),
                id,
                branch.getCode(),
                branch.getName(),
                branch.getAddress(),
                toPhone(branch.getPhone()),
                display,
                hours,
                toStatusLabel(branch.getStatus())
        );
    }

    static String toPhone(String phone) {
        return phone == null ? "" : phone;
    }

    static String toStatusLabel(BranchStatus status) {
        return status == BranchStatus.ACTIVE ? ACTIVE_LABEL : SUSPENDED_LABEL;
    }

    private static List<WeeklyHours> toWeeklyHours(com.openstudy.modules.branch.entity.Branch branch) {
        // Legacy branches without a configured schedule are explicitly closed, never inferred open.
        return IntStream.rangeClosed(1, 7)
                .mapToObj(day -> findWeeklyHours(branch, day))
                .toList();
    }

    private static WeeklyHours findWeeklyHours(com.openstudy.modules.branch.entity.Branch branch, int day) {
        return branch.getWeeklyHours()
                .stream()
                .filter(hour -> hour.getWeekday() == day)
                .findFirst()
                .map(hour -> new WeeklyHours(
                        hour.getWeekday(),
                        hour.isClosed(),
                        hour.getOpensMinute(),
                        hour.getClosesMinute()
                ))
                .orElse(new WeeklyHours(day, true, null, null));
    }

    private static String toDisplay(WeeklyHours hours) {
        String time = hours.isClosed()
                ? "휴무"
                : toTime(hours.opensMinute()) + "~" + toTime(hours.closesMinute());

        return hours.weekday() + "요일 " + time;
    }

    private static String toTime(int minute) {
        String nextDay = minute >= MINUTES_PER_DAY ? "익일 " : "";
        int minuteOfDay = minute % MINUTES_PER_DAY;

        return nextDay + String.format("%02d:%02d", minuteOfDay / MINUTES_PER_HOUR, minute % MINUTES_PER_HOUR);
    }
}
