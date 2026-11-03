package com.openstudy.modules.locker.dto.response;

import java.time.Instant;

/** A locker use as the cross-branch summary may show it: where and when, with no end until the return. */
public record CustomerLockerUsage(long branchId, Instant startAt, Instant endAt) {}
