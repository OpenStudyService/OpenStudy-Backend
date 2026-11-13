package com.openstudy.modules.ai.service;

import com.openstudy.config.DomainLensProperties;
import com.openstudy.domainlens.ai.LiveModelGatewayFactory;
import com.openstudy.domainlens.api.prompt.PromptLimits;
import com.openstudy.domainlens.api.spi.ModelGateway;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * #233: the model the generation path plans with. Only the live model can plan a new query; in playback mode there is
 * none and unregistered questions stay unsupported, as before. The model returns a semantic plan the server compiles
 * (#232 decision: fewer tokens, and the server owns the view).
 */
@Component
@RequiredArgsConstructor
public class OpenStudyGenerationModel {
    private final OpenStudyPlaybackGateway modes;
    private final DomainLensProperties properties;
    private volatile ModelGateway live;

    public Optional<ModelGateway> gateway() {
        if (!"live".equals(modes.activeMode())) return Optional.empty();
        if (live == null) {
            synchronized (this) {
                if (live == null) {
                    var m = properties.getModel();
                    live = LiveModelGatewayFactory.create(m.getBaseUrl(), m.getApiKey(), m.getName(), m.getTimeout(),
                            PromptLimits.defaults(), m.getEnableThinking(), m.getReasoningEffort(), m.getMaxCompletionTokens())
                            .planning(true);
                }
            }
        }
        return Optional.of(live);
    }
}
