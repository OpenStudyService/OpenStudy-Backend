package com.openstudy.common.exception;

import java.util.Map;

/** Explicit, client-safe errors. Never construct messages from SQL or upstream exception text. */
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final Map<String, String> fieldErrors;
    private final Long latestVersion;
    private final String reloadUrl;

    private ApiException(int status, String code, String message, Map<String, String> fields,
            Long latestVersion, String reloadUrl) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = fields == null ? null : Map.copyOf(fields);
        this.latestVersion = latestVersion;
        this.reloadUrl = reloadUrl;
    }

    public static ApiException validation(String field, String message) {
        return new ApiException(422, "VALIDATION_FAILED", "입력 형식을 확인해 주세요.", Map.of(field, message), null, null);
    }

    /** A safe 422 without field details, e.g. an invitation link whose failure reason must not be revealed. */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(422, code, message, null, null, null);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message, null, null, null);
    }

    public static ApiException versionConflict(long latestVersion, String reloadUrl) {
        if (latestVersion < 1 || latestVersion > 9_007_199_254_740_991L
                || reloadUrl == null || !reloadUrl.startsWith("/api/") || reloadUrl.contains("\r") || reloadUrl.contains("\n")) {
            throw new IllegalArgumentException("Invalid conflict metadata");
        }
        return new ApiException(409, "VERSION_CONFLICT", "최신 정보를 확인한 뒤 다시 저장해 주세요.",
                null, latestVersion, reloadUrl);
    }

    public int status() { return status; }
    public ApiError error(String requestId) {
        return new ApiError(code, getMessage(), requestId, fieldErrors, latestVersion, reloadUrl);
    }
}
