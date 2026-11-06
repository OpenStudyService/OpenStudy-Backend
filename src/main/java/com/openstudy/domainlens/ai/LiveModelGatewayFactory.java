package com.openstudy.domainlens.ai;

import com.openstudy.domainlens.api.prompt.PromptLimits;
import java.net.URI;
import java.time.Duration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Server-only construction. No endpoint/model defaults and no SDK retries. */
public final class LiveModelGatewayFactory {
    private LiveModelGatewayFactory() {}

    public static SpringAiModelGateway create(String baseUrl, String apiKey, String model, Duration timeout) {
        return create(baseUrl,apiKey,model,timeout,PromptLimits.defaults());
    }

    public static SpringAiModelGateway create(String baseUrl, String apiKey, String model, Duration timeout, PromptLimits limits) {
        return create(baseUrl,apiKey,model,timeout,limits,null);
    }

    /** Optional local-provider extension; omitted for providers using standard OpenAI fields only. */
    public static SpringAiModelGateway create(String baseUrl, String apiKey, String model, Duration timeout, PromptLimits limits, Boolean enableThinking) {
        return create(baseUrl,apiKey,model,timeout,limits,enableThinking,null);
    }

    public static SpringAiModelGateway create(String baseUrl, String apiKey, String model, Duration timeout, PromptLimits limits, Boolean enableThinking, String reasoningEffort) {
        return create(baseUrl,apiKey,model,timeout,limits,enableThinking,reasoningEffort,4096);
    }

    public static SpringAiModelGateway create(String baseUrl,String apiKey,String model,Duration timeout,PromptLimits limits,Boolean enableThinking,String reasoningEffort,int maxCompletionTokens) {
        require(baseUrl,"base URL");require(apiKey,"API key");require(model,"model ID");
        URI uri;
        try { uri=URI.create(baseUrl); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Invalid model base URL"); }
        if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) || uri.getHost()==null
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("Invalid model base URL");
        if (timeout==null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Positive model timeout is required");
        // The 2.x SDK expects a complete API base, including /v1 for OpenAI-compatible servers.
        var options=OpenAiChatOptions.builder().baseUrl(baseUrl).apiKey(apiKey).model(model)
                .timeout(timeout).maxCompletionTokens(maxCompletionTokens).maxRetries(0).store(false);
        if(enableThinking!=null)options.extraBody(java.util.Map.of("enable_thinking",enableThinking));
        if(reasoningEffort!=null){require(reasoningEffort,"reasoning effort");options.reasoningEffort(reasoningEffort);}
        return new SpringAiModelGateway(OpenAiChatModel.builder().options(options.build()).build(),model,limits);
    }

    private static void require(String value, String name) {
        if (value==null || value.isBlank()) throw new IllegalArgumentException("Model "+name+" is required");
    }
}
