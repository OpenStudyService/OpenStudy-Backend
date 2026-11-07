package com.openstudy.modules.workforce.dto.response;

import java.time.Instant;

/**
 * A colleague's shift on the home card (2026-11-05 user decision): the branch, the colleague's name and the planned times
 * only. No shift ID, employee ID, break, attendance or note leaves the module, so nothing here opens a detail.
 */
public record ShiftColleague(long branchId, String employeeName, Instant startsAt, Instant endsAt) {}
