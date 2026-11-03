package com.openstudy.modules.workforce.dto.response;

import java.time.Instant;

/**
 * Contract schema {@code ShiftConflict}: only the part of the requested shift that is taken. A shift the requester cannot
 * see and an approved leave carry no target, so nothing of them but that overlap leaves the server (M8-D05·D06).
 */
public record ShiftConflict(String kind, boolean visible, String label, Instant overlapStartsAt, Instant overlapEndsAt,
        ShiftConflictTarget shift) {}
