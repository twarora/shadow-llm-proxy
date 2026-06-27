package com.shadowproxy.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Provider adapter layer.
 *
 * <p>Different LLM providers put the answer text in different fields of their response. This
 * adapter's ONLY job is to dig out that single content string, so the rest of the system only ever
 * deals with a plain string. Usage, tokens, latency and every other envelope field are ignored.
 *
 * <ul>
 *   <li>OpenAI    → {@code choices[0].message.content}</li>
 *   <li>Anthropic → {@code content[0].text}</li>
 *   <li>mock      → {@code output}</li>
 * </ul>
 *
 * Adding a new provider means adding one branch here; nothing downstream changes.
 */
@Component
public class ProviderAdapter {

    private final ObjectMapper mapper;

    public ProviderAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String extractContent(String provider, String rawJson) {
        if (provider == null) {
            throw new AdapterException("provider must not be null");
        }
        JsonNode root;
        try {
            root = mapper.readTree(rawJson);
        } catch (Exception e) {
            throw new AdapterException("response was not valid JSON for provider '" + provider + "'", e);
        }

        return switch (provider.toLowerCase()) {
            case "openai" -> require(
                    root.path("choices").path(0).path("message").path("content"),
                    provider, "choices[0].message.content");
            case "anthropic" -> require(
                    root.path("content").path(0).path("text"),
                    provider, "content[0].text");
            case "mock" -> require(
                    root.path("output"),
                    provider, "output");
            default -> throw new AdapterException("unknown provider: " + provider);
        };
    }

    private String require(JsonNode node, String provider, String path) {
        if (node.isMissingNode() || node.isNull() || !node.isValueNode()) {
            throw new AdapterException(
                    "could not find content for provider '" + provider + "' at path '" + path + "'");
        }
        return node.asText();
    }
}
