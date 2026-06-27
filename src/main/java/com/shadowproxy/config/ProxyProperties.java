package com.shadowproxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Central configuration for the proxy. Bound from the {@code app.*} section of application.yml.
 *
 * <p>The {@code provider} field on each model block selects which adapter is used to pull the
 * content string out of that provider's response envelope. Nothing about a model is passed per
 * request; everything lives here so models can be swapped (mock vs real LLM) without code changes.
 */
@ConfigurationProperties(prefix = "app")
public class ProxyProperties {

    private ModelConfig primary = new ModelConfig();
    private ModelConfig shadow = new ModelConfig();
    private ModelConfig judge = new ModelConfig();
    private Evaluation evaluation = new Evaluation();
    private ShadowMode shadowMode = new ShadowMode();
    private ShadowExecutor shadowExecutor = new ShadowExecutor();

    public ModelConfig getPrimary() {
        return primary;
    }

    public void setPrimary(ModelConfig primary) {
        this.primary = primary;
    }

    public ModelConfig getShadow() {
        return shadow;
    }

    public void setShadow(ModelConfig shadow) {
        this.shadow = shadow;
    }

    public ModelConfig getJudge() {
        return judge;
    }

    public void setJudge(ModelConfig judge) {
        this.judge = judge;
    }

    public Evaluation getEvaluation() {
        return evaluation;
    }

    public void setEvaluation(Evaluation evaluation) {
        this.evaluation = evaluation;
    }

    public ShadowMode getShadowMode() {
        return shadowMode;
    }

    public void setShadowMode(ShadowMode shadowMode) {
        this.shadowMode = shadowMode;
    }

    public ShadowExecutor getShadowExecutor() {
        return shadowExecutor;
    }

    public void setShadowExecutor(ShadowExecutor shadowExecutor) {
        this.shadowExecutor = shadowExecutor;
    }

    /** A single LLM endpoint definition (primary, shadow, or judge). */
    public static class ModelConfig {
        private String provider = "mock";
        private String baseUrl;
        private String model;
        /** Name of the environment variable holding the API key (kept out of config files). */
        private String apiKeyEnv;
        private int timeoutSeconds = 30;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKeyEnv() {
            return apiKeyEnv;
        }

        public void setApiKeyEnv(String apiKeyEnv) {
            this.apiKeyEnv = apiKeyEnv;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    /** Evaluation knobs: the cheap similarity pre-filter threshold and embedding choice. */
    public static class Evaluation {
        private double similarityThreshold = 0.90;
        private String embeddingProvider = "lexical";
        private String embeddingModel = "bow-cosine";

        public double getSimilarityThreshold() {
            return similarityThreshold;
        }

        public void setSimilarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
        }

        public String getEmbeddingProvider() {
            return embeddingProvider;
        }

        public void setEmbeddingProvider(String embeddingProvider) {
            this.embeddingProvider = embeddingProvider;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }
    }

    public static class ShadowMode {
        private boolean enabled = true;
        private boolean logMismatches = true;
        private String mismatchLogPath = "./logs/mismatches.jsonl";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isLogMismatches() {
            return logMismatches;
        }

        public void setLogMismatches(boolean logMismatches) {
            this.logMismatches = logMismatches;
        }

        public String getMismatchLogPath() {
            return mismatchLogPath;
        }

        public void setMismatchLogPath(String mismatchLogPath) {
            this.mismatchLogPath = mismatchLogPath;
        }
    }

    public static class ShadowExecutor {
        private int poolSize = 8;
        private int queueCapacity = 500;

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }
}
