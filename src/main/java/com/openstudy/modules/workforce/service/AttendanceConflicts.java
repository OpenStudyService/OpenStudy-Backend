package com.openstudy.modules.workforce.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.openstudy.modules.workforce.dto.response.AttendanceConflict;
import com.openstudy.modules.workforce.entity.AttendanceBreak;
import com.openstudy.modules.workforce.entity.AttendanceRecord;

/**
 * The two checks of a corrected attendance (M12-D08). Its stretch [clock-in, clock-out) must not meet another attendance
 * of the same employee; an attendance still open ends "now". The 409 lists only the overlaps, with the hiding rule of
 * {@link ShiftConflicts}: one the reader may see is named, the others are merged into one entry per continuous stretch.
 * And every break must lie inside the stretch, without overlapping another.
 */
final class AttendanceConflicts {

    private static final String VISIBLE_LABEL = "근태";
    private static final String HIDDEN_LABEL = "다른 지점 근태";

    private AttendanceConflicts() {
    }

    private record Span(Instant start, Instant end) {
    }

    /** A break where it would be after the correction. */
    record Placed(long id, Instant start, Instant end) {

        static Placed of(AttendanceBreak item) {
            return new Placed(item.getId(), item.getStartedAt(), item.getEndedAt());
        }
    }

    static List<AttendanceConflict> findOverlaps(
            Instant start,
            Instant end,
            List<AttendanceRecord> others,
            Instant now,
            Predicate<AttendanceRecord> visible
    ) {
        List<AttendanceConflict> result = new ArrayList<>();
        List<Span> hidden = new ArrayList<>();

        for (AttendanceRecord other : others) {
            Instant otherEnd = other.getClockOutAt() == null ? now : other.getClockOutAt();
            Instant from = start.isAfter(other.getClockInAt()) ? start : other.getClockInAt();
            Instant to = end.isBefore(otherEnd) ? end : otherEnd;

            if (!from.isBefore(to)) {
                continue;
            }

            if (visible.test(other)) {
                result.add(new AttendanceConflict(true, VISIBLE_LABEL, from, to, other.getId().toString()));
            } else {
                hidden.add(new Span(from, to));
            }
        }

        hidden.sort(Comparator.comparing(Span::start));

        for (Span span : mergeSpans(hidden)) {
            result.add(new AttendanceConflict(false, HIDDEN_LABEL, span.start(), span.end(), null));
        }

        result.sort(Comparator.comparing(AttendanceConflict::overlapStartsAt)
                .thenComparing(AttendanceConflict::visible));

        return result;
    }

    /** The breaks, with one of them already moved, that lie outside [clockIn, clockOut) or overlap another; ids ascending. */
    static List<String> findMisplaced(Instant clockIn, Instant clockOut, List<Placed> breaks) {
        Set<Long> bad = new HashSet<>();

        for (Placed item : breaks) {
            boolean outside = item.start().isBefore(clockIn) || item.end().isAfter(clockOut);
            boolean empty = !item.start().isBefore(item.end());

            if (outside || empty) {
                bad.add(item.id());
            }
        }

        List<Placed> sorted = breaks.stream()
                .sorted(Comparator.comparing(Placed::start).thenComparing(Placed::id))
                .toList();

        for (int one = 0; one < sorted.size(); one++) {
            for (int other = one + 1; other < sorted.size(); other++) {
                if (!sorted.get(other).start().isBefore(sorted.get(one).end())) {
                    break;
                }

                bad.add(sorted.get(one).id());
                bad.add(sorted.get(other).id());
            }
        }

        return bad.stream()
                .sorted()
                .map(id -> Long.toString(id))
                .toList();
    }

    private static List<Span> mergeSpans(List<Span> sortedSpans) {
        List<Span> merged = new ArrayList<>();

        for (Span span : sortedSpans) {
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
