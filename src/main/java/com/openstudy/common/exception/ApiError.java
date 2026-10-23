package com.openstudy.common.exception;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, String requestId,
        Map<String, String> fieldErrors, Long latestVersion, String reloadUrl) {
}
