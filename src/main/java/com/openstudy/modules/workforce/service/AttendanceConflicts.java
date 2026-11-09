package com.openstudy.modules.workforce.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
    private record Span(Instant start, Instant end) {}

    private AttendanceConflicts() {}

    static List<AttendanceConflict> overlaps(Instant start, Instant end, List<AttendanceRecord> others, Instant now,
            Predicate<AttendanceRecord> visible) {
        var result = new ArrayList<AttendanceConflict>();
        var hidden = new ArrayList<Span>();
        for (var other : others) {
            Instant otherEnd = other.getClockOutAt() == null ? now : other.getClockOutAt();
            Instant from = start.isAfter(other.getClockInAt()) ? start : other.getClockInAt();
            Instant to = end.isBefore(otherEnd) ? end : otherEnd;
            if (!from.isBefore(to)) continue;
            if (visible.test(other)) result.add(new AttendanceConflict(true, "근태", from, to, other.getId().toString()));
            else hidden.add(new Span(from, to));
        }
        hidden.sort(Comparator.comparing(Span::start));
        var merged = new ArrayList<Span>();
        for (var span : hidden) {
            if (!merged.isEmpty() && !span.start().isAfter(merged.getLast().end())) {
                var last = merged.removeLast();
                merged.add(new Span(last.start(), span.end().isAfter(last.end()) ? span.end() : last.end()));
            } else {
                merged.add(span);
            }
        }
        for (var span : merged) result.add(new AttendanceConflict(false, "다른 지점 근태", span.start(), span.end(), null));
        result.sort(Comparator.comparing(AttendanceConflict::overlapStartsAt).thenComparing(AttendanceConflict::visible));
        return result;
    }

    /** The breaks, with one of them already moved, that lie outside [clockIn, clockOut) or overlap another; ids ascending. */
    static List<String> misplaced(Instant clockIn, Instant clockOut, List<Placed> breaks) {
        var bad = new HashSet<Long>();
        for (var item : breaks)
            if (item.start().isBefore(clockIn) || item.end().isAfter(clockOut) || !item.start().isBefore(item.end())) bad.add(item.id());
        var sorted = breaks.stream().sorted(Comparator.comparing(Placed::start).thenComparing(Placed::id)).toList();
        for (int one = 0; one < sorted.size(); one++)
            for (int other = one + 1; other < sorted.size() && sorted.get(other).start().isBefore(sorted.get(one).end()); other++) {
                bad.add(sorted.get(one).id());
                bad.add(sorted.get(other).id());
            }
        return bad.stream().sorted().map(id -> Long.toString(id)).toList();
    }

    /** A break where it would be after the correction. */
    record Placed(long id, Instant start, Instant end) {
        static Placed of(AttendanceBreak item) { return new Placed(item.getId(), item.getStartedAt(), item.getEndedAt()); }
    }
}
