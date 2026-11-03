package com.openstudy.modules.workforce.dto.response;

import java.util.List;

/** Contract schema {@code ShiftConflictError}: the common error fields of a 409 SHIFT_CONFLICT plus what is taken. */
public record ShiftConflictError(String code, String message, String requestId, List<ShiftConflict> conflicts) {}
