package com.openstudy.modules.workforce.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import com.openstudy.modules.workforce.dto.response.ShiftConflict;
import com.openstudy.modules.workforce.dto.response.ShiftConflictTarget;
import com.openstudy.modules.workforce.entity.LeaveRequest;
import com.openstudy.modules.workforce.entity.Shift;

/**
 * The 409 body of a requested shift [start, end) that something already holds (M8-D05·D06). Intervals are half-open, so
 * 09:00~12:00 and 12:00~18:00 do not meet. Each entry is only the intersection with the request. A shift the requester
 * may see is named; the others, and every approved leave, are merged in time order into one entry per continuous
 * stretch (touching ones too), so neither their number nor their own bounds can be read from the answer.
 */
final class ShiftConflicts {
    private record Span(Instant start, Instant end) {}

    private ShiftConflicts() {}

    static List<ShiftConflict> of(Instant start, Instant end, List<Shift> shifts, Predicate<Shift> visible, List<LeaveRequest> leaves) {
        var result = new ArrayList<ShiftConflict>();
        var hidden = new ArrayList<Span>();
        for (var shift : shifts) {
            var overlap = overlap(start, end, shift.getStartsAt(), shift.getEndsAt());
            if (overlap == null) continue;
            if (visible.test(shift)) {
                result.add(new ShiftConflict("근무", true, "근무", overlap.start(), overlap.end(), new ShiftConflictTarget(shift.getId().toString(),
                        Long.toString(shift.getBranchId()), shift.getStartsAt(), shift.getEndsAt())));
            } else {
                hidden.add(overlap);
            }
        }
        for (var span : merge(hidden)) result.add(new ShiftConflict("근무", false, "다른 지점 근무", span.start(), span.end(), null));
        var away = new ArrayList<Span>();
        for (var leave : leaves) {
            // The whole Seoul day, also for half a day: [starts_on 00:00, the day after ends_on 00:00).
            var overlap = overlap(start, end, ShiftInputs.startOf(leave.getStartsOn()), ShiftInputs.startOf(leave.getEndsOn().plusDays(1)));
            if (overlap != null) away.add(overlap);
        }
        for (var span : merge(away)) result.add(new ShiftConflict("휴가", false, "승인 휴가", span.start(), span.end(), null));
        // Shifts of one employee never overlap each other, so only a leave can share a start with a shift; the shift comes first.
        result.sort(Comparator.comparing(ShiftConflict::overlapStartsAt).thenComparing(conflict -> "휴가".equals(conflict.kind())));
        return result;
    }

    private static Span overlap(Instant start, Instant end, Instant otherStart, Instant otherEnd) {
        Instant from = start.isAfter(otherStart) ? start : otherStart;
        Instant to = end.isBefore(otherEnd) ? end : otherEnd;
        return from.isBefore(to) ? new Span(from, to) : null;
    }

    private static List<Span> merge(List<Span> spans) {
        var sorted = spans.stream().sorted(Comparator.comparing(Span::start)).toList();
        var merged = new ArrayList<Span>();
        for (var span : sorted) {
            if (!merged.isEmpty() && !span.start().isAfter(merged.getLast().end())) {
                var last = merged.removeLast();
                merged.add(new Span(last.start(), span.end().isAfter(last.end()) ? span.end() : last.end()));
            } else {
                merged.add(span);
            }
        }
        return merged;
    }
}
