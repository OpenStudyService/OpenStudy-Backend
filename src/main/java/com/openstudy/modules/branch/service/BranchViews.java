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
    private BranchViews() {}

    static Branch toDto(com.openstudy.modules.branch.entity.Branch b) {
        String id = Long.toString(b.getId());
        var hours = hours(b);
        String display = hours.stream().map(h -> h.weekday() + "요일 "
                + (h.isClosed() ? "휴무" : time(h.opensMinute()) + "~" + time(h.closesMinute())))
                .collect(Collectors.joining(", "));
        return new Branch(id, ApiVersion.fromDatabase(b.getVersion()), b.getCreatedAt().toString(), b.getUpdatedAt().toString(),
                id, b.getCode(), b.getName(), b.getAddress(), b.getPhone() == null ? "" : b.getPhone(), display, hours,
                b.getStatus() == BranchStatus.ACTIVE ? "운영 중" : "운영 중지");
    }

    private static List<WeeklyHours> hours(com.openstudy.modules.branch.entity.Branch b) {
        // Legacy branches without a configured schedule are explicitly closed, never inferred open.
        return IntStream.rangeClosed(1, 7).mapToObj(day -> b.getWeeklyHours().stream().filter(h -> h.getWeekday() == day)
                .findFirst().map(h -> new WeeklyHours(h.getWeekday(), h.isClosed(), h.getOpensMinute(), h.getClosesMinute()))
                .orElse(new WeeklyHours(day, true, null, null))).toList();
    }

    private static String time(int minute) {
        return (minute >= 1440 ? "익일 " : "") + String.format("%02d:%02d", (minute % 1440) / 60, minute % 60);
    }
}
