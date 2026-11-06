package com.openstudy.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties bound under the {@code domainlens.*} prefix for OpenStudy × DomainLens.
 */
@ConfigurationProperties(prefix = "domainlens")
public class DomainLensProperties {

    private final Embedding embedding = new Embedding();
    private final Model model = new Model();
    private final Prompt prompt = new Prompt();
    private final Clock clock = new Clock();

    public Embedding getEmbedding() {
        return embedding;
    }

    public Model getModel() {
        return model;
    }

    public Prompt getPrompt() {
        return prompt;
    }

    public Clock getClock() {
        return clock;
    }

    public static class Embedding {
        private String provider = "onnx";
        private String directory = ".models/koen-e5-tiny-q8";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }
    }

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

        public Mode getMode() {
            return mode;
        }

        public void setMode(Mode mode) {
            this.mode = mode;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Boolean getEnableThinking() {
            return enableThinking;
        }

        public void setEnableThinking(Boolean enableThinking) {
            this.enableThinking = enableThinking;
        }

        public int getMaxCompletionTokens() {
            return maxCompletionTokens;
        }

        public void setMaxCompletionTokens(int maxCompletionTokens) {
            this.maxCompletionTokens = maxCompletionTokens;
        }

        public boolean isSemanticPlanning() {
            return semanticPlanning;
        }

        public void setSemanticPlanning(boolean semanticPlanning) {
            this.semanticPlanning = semanticPlanning;
        }

        public String getReasoningEffort() {
            return reasoningEffort;
        }

        public void setReasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public static class Prompt {
        private int questionBytes = 8192;
        private int packageBytes = 65536;

        public int getQuestionBytes() {
            return questionBytes;
        }

        public void setQuestionBytes(int questionBytes) {
            this.questionBytes = questionBytes;
        }

        public int getPackageBytes() {
            return packageBytes;
        }

        public void setPackageBytes(int packageBytes) {
            this.packageBytes = packageBytes;
        }
    }

    public static class Clock {
        private String asOf = "2026-11-02T14:00:00+09:00";
        private String zoneDisplay = "Asia/Seoul";

        public String getAsOf() {
            return asOf;
        }

        public void setAsOf(String asOf) {
            this.asOf = asOf;
        }

        public String getZoneDisplay() {
            return zoneDisplay;
        }

        public void setZoneDisplay(String zoneDisplay) {
            this.zoneDisplay = zoneDisplay;
        }
    }
}
