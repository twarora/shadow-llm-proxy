package com.shadowproxy.llm;

import com.shadowproxy.config.ProxyProperties.ModelConfig;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin HTTP client for calling an LLM endpoint. Returns the raw response body as a string; turning
 * that body into a content string is the {@link com.shadowproxy.adapter.ProviderAdapter}'s job.
 *
 * <p>The request body here is intentionally generic ({@code model} + {@code prompt}) which is what
 * the bundled mock endpoints expect. For a real provider you would format the provider-specific
 * request shape (e.g. OpenAI {@code messages}); only the response side needs the adapter.
 */
@Component
public class LlmClient {

    private final RestClient.Builder builder;

    public LlmClient(RestClient.Builder builder) {
        this.builder = builder;
    }

    /** Generate call: sends the user prompt to a primary/shadow endpoint. */
    public String generate(ModelConfig cfg, String prompt) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", cfg.getModel() == null ? "" : cfg.getModel());
        body.put("prompt", prompt);
        return post(cfg, body);
    }

    /** Judge call: sends the evaluator system prompt plus the comparison payload. */
    public String judge(ModelConfig cfg, String systemPrompt, String userMessage) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", cfg.getModel() == null ? "" : cfg.getModel());
        body.put("system", systemPrompt);
        body.put("prompt", userMessage);
        return post(cfg, body);
    }

    private String post(ModelConfig cfg, Map<String, Object> body) {
        RestClient client = clientFor(cfg);
        RestClient.RequestBodySpec spec = client.post()
                .uri(cfg.getBaseUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);

        String apiKey = resolveApiKey(cfg);
        if (apiKey != null && !apiKey.isBlank()) {
            spec = spec.header("Authorization", "Bearer " + apiKey);
        }

        return spec.body(body).retrieve().body(String.class);
    }

    private RestClient clientFor(ModelConfig cfg) {
        long millis = Duration.ofSeconds(Math.max(1, cfg.getTimeoutSeconds())).toMillis();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Math.min(Integer.MAX_VALUE, millis));
        factory.setReadTimeout((int) Math.min(Integer.MAX_VALUE, millis));
        return builder.clone().requestFactory(factory).build();
    }

    private String resolveApiKey(ModelConfig cfg) {
        if (cfg.getApiKeyEnv() == null || cfg.getApiKeyEnv().isBlank()) {
            return null;
        }
        return System.getenv(cfg.getApiKeyEnv());
    }
}
