package com.openstudy.common.web;

import com.openstudy.common.exception.ApiException;

/** API versions are positive JS-safe integers; persisted versions are zero based. */
public final class ApiVersion {
    public static final long MAX = 9_007_199_254_740_991L;
    private ApiVersion() {}

    public static long fromDatabase(long value) {
        if (value < 0 || value >= MAX) throw new IllegalStateException("Database version outside API range");
        return value + 1;
    }

    public static long toDatabase(long value) {
        if (value < 1 || value > MAX) throw ApiException.validation("version", "올바른 버전을 입력해 주세요.");
        return value - 1;
    }

    /** Call only after resource authorization, so conflict metadata cannot reveal other scopes. */
    public static void requireCurrent(long requestedApiVersion, long databaseVersion, String reloadUrl) {
        if (toDatabase(requestedApiVersion) != databaseVersion) {
            throw ApiException.versionConflict(fromDatabase(databaseVersion), reloadUrl);
        }
    }
}
