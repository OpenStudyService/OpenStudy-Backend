package com.openstudy.config;

import java.time.Duration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties bound under the {@code domainlens.*} prefix for OpenStudy × DomainLens.
 */
@Getter
@ConfigurationProperties(prefix = "domainlens")
public class DomainLensProperties {

    private final Embedding embedding = new Embedding();
    private final Model model = new Model();
    private final Prompt prompt = new Prompt();
    private final Clock clock = new Clock();

    @Getter
    @Setter
    public static class Embedding {
        private String provider = "onnx";
        private String directory = ".models/koen-e5-tiny-q8";
    }

    @Getter
    @Setter
    public static class Model {

        public enum Mode { PLAYBACK, LIVE }

        private Mode mode = Mode.PLAYBACK;
        private String baseUrl = "https://token-plan.maas.qwencloudapi.com/compatible-mode/v1";
        private String apiKey;
        private String name = "qwen3.8-flash";
        private Boolean enableThinking = Boolean.FALSE;
        private int maxCompletionTokens = 4096;
        private boolean semanticPlanning;
        private String reasoningEffort;
        private Duration timeout = Duration.ofSeconds(60);
    }

    @Getter
    @Setter
    public static class Prompt {
        private int questionBytes = 8192;
        private int packageBytes = 65536;
    }

    @Getter
    @Setter
    public static class Clock {
        private String asOf = "2026-11-02T14:00:00+09:00";
        private String zoneDisplay = "Asia/Seoul";
    }
}
