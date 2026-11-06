package com.openstudy.domainlens.api.prompt;
import org.jspecify.annotations.Nullable;
/** Measured provider usage. Null token counts mean the provider did not report them. */
public record ModelUsage(String model,@Nullable Integer inputTokens,@Nullable Integer outputTokens,
                         int call,int inputTokenBudget,int outputTokenBudget,String costVersion,String status) {}
