package com.openstudy.common.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A 409 of the M13 contract (operations-extension-api.md "추가 오류 코드") with the target information of its code,
 * e.g. the closure that already holds a day.
 *
 * <p>The body is {@code OperationsConflictError}: the {@link ApiError} fields plus the targets this conflict names.
 * It is not an {@link ApiException}, so its own handler always writes the targets.
 */
public class OperationsConflict extends RuntimeException {

    private final String code;
    private final Map<String, Object> targets;

    private OperationsConflict(String code, String message, Map<String, Object> targets) {
        super(message);
        this.code = code;
        this.targets = Collections.unmodifiableMap(new LinkedHashMap<>(targets));
    }

    /**
     * Targets as name/value pairs, written as given: IDs and dates as strings, counts as numbers.
     * A null value is left out of the body ("해당 없는 필드는 담지 않는다").
     */
    public static OperationsConflict of(String code, String message, Object... namesAndValues) {
        Map<String, Object> targets = new LinkedHashMap<>();

        for (int index = 0; index < namesAndValues.length; index += 2) {
            Object value = namesAndValues[index + 1];

            if (value != null) {
                targets.put((String) namesAndValues[index], value);
            }
        }

        return new OperationsConflict(code, message, targets);
    }

    public Map<String, Object> body(String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();

        body.put("code", code);
        body.put("message", getMessage());
        body.put("requestId", requestId);
        body.putAll(targets);

        return body;
    }
}
