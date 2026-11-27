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

    private static final String SHIFT_KIND = "근무";
    private static final String LEAVE_KIND = "휴가";
    private static final String HIDDEN_SHIFT_LABEL = "다른 지점 근무";
    private static final String LEAVE_LABEL = "승인 휴가";

    private ShiftConflicts() {
    }

    private record Span(Instant start, Instant end) {
    }

    static List<ShiftConflict> toConflicts(
            Instant start,
            Instant end,
            List<Shift> shifts,
            Predicate<Shift> visible,
            List<LeaveRequest> leaves
    ) {
        List<ShiftConflict> result = new ArrayList<>();
        List<Span> hidden = new ArrayList<>();

        for (Shift shift : shifts) {
            Span overlap = findOverlap(start, end, shift.getStartsAt(), shift.getEndsAt());

            if (overlap == null) {
                continue;
            }

            if (visible.test(shift)) {
                ShiftConflictTarget target = new ShiftConflictTarget(
                        shift.getId().toString(),
                        Long.toString(shift.getBranchId()),
                        shift.getStartsAt(),
                        shift.getEndsAt()
                );
                result.add(new ShiftConflict(SHIFT_KIND, true, SHIFT_KIND, overlap.start(), overlap.end(), target));
            } else {
                hidden.add(overlap);
            }
        }

        for (Span span : mergeSpans(hidden)) {
            result.add(new ShiftConflict(SHIFT_KIND, false, HIDDEN_SHIFT_LABEL, span.start(), span.end(), null));
        }

        List<Span> away = new ArrayList<>();

        for (LeaveRequest leave : leaves) {
            // The whole Seoul day, also for half a day: [starts_on 00:00, the day after ends_on 00:00).
            Instant leaveStart = ShiftInputs.toStartOf(leave.getStartsOn());
            Instant leaveEnd = ShiftInputs.toStartOf(leave.getEndsOn().plusDays(1));
            Span overlap = findOverlap(start, end, leaveStart, leaveEnd);

            if (overlap != null) {
                away.add(overlap);
            }
        }

        for (Span span : mergeSpans(away)) {
            result.add(new ShiftConflict(LEAVE_KIND, false, LEAVE_LABEL, span.start(), span.end(), null));
        }

        // Shifts of one employee never overlap each other, so only a leave can share a start with a shift; the shift
        // comes first.
        result.sort(Comparator.comparing(ShiftConflict::overlapStartsAt)
                .thenComparing(conflict -> LEAVE_KIND.equals(conflict.kind())));

        return result;
    }

    private static Span findOverlap(Instant start, Instant end, Instant otherStart, Instant otherEnd) {
        Instant from = start.isAfter(otherStart) ? start : otherStart;
        Instant to = end.isBefore(otherEnd) ? end : otherEnd;

        return from.isBefore(to) ? new Span(from, to) : null;
    }

    private static List<Span> mergeSpans(List<Span> spans) {
        List<Span> sorted = spans.stream()
                .sorted(Comparator.comparing(Span::start))
                .toList();
        List<Span> merged = new ArrayList<>();

        for (Span span : sorted) {
            boolean touchesLast = !merged.isEmpty() && !span.start().isAfter(merged.getLast().end());

            if (touchesLast) {
                Span last = merged.removeLast();
                Instant end = span.end().isAfter(last.end()) ? span.end() : last.end();
                merged.add(new Span(last.start(), end));
            } else {
                merged.add(span);
            }
        }

        return merged;
    }
}
