package com.openstudy.domainlens.api.prompt;

import com.openstudy.domainlens.api.error.*;
import java.nio.charset.StandardCharsets;

/** Byte limits, deliberately independent of a provider's token estimator. */
public record PromptLimits(int questionBytes, int packageBytes) {
    public PromptLimits {
        if(questionBytes<1 || packageBytes<questionBytes)throw new IllegalArgumentException("Invalid prompt limits");
    }
    public static PromptLimits defaults() { return new PromptLimits(8192,65536); }
    public void question(String text) { check(text,questionBytes); }
    public void outbound(String json) { check(json,packageBytes); }
    private static void check(String value,int limit) {
        if(value.getBytes(StandardCharsets.UTF_8).length>limit)
            throw new EngineException(EngineErrorCode.BUDGET_EXCEEDED,"Prompt size budget exceeded ("+value.getBytes(StandardCharsets.UTF_8).length+" > "+limit+" bytes)");
    }
}
