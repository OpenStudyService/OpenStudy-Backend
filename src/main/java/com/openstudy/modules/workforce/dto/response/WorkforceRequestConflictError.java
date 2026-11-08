package com.openstudy.modules.workforce.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Contract schema {@code WorkforceRequestConflictError}: the common error fields plus the target information of the
 * code, e.g. the shifts in the way of a leave approval or the days missing from the balance. Fields a code does not
 * use are left out.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkforceRequestConflictError(String code, String message, String requestId, Map<String, String> fieldErrors,
        Long latestVersion, String reloadUrl, List<ShiftConflict> conflicts, BigDecimal requiredDays, BigDecimal availableDays,
        List<String> overlappingRequestIds) {}
