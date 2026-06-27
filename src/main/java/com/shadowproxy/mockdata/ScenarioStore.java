package com.shadowproxy.mockdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the bundled mock scenarios ({@code mockdata/llm-scenarios.json}) at startup and indexes them
 * so the mock LLM endpoints can return scenario-mapped responses.
 *
 * <p>This is mock/test infrastructure only — it lets a real prompt drive deterministic primary,
 * shadow, and judge responses end-to-end.
 */
@Component
public class ScenarioStore {

    private static final Logger log = LoggerFactory.getLogger(ScenarioStore.class);
    private static final String RESOURCE = "/mockdata/llm-scenarios.json";

    private final ObjectMapper mapper;
    private final List<JsonNode> scenarios = new ArrayList<>();
    private final Map<String, JsonNode> byPrompt = new LinkedHashMap<>();

    public ScenarioStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    void load() {
        try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                log.warn("scenario fixtures not found on classpath at {}", RESOURCE);
                return;
            }
            JsonNode root = mapper.readTree(in);
            for (JsonNode scenario : root.path("scenarios")) {
                scenarios.add(scenario);
                String prompt = scenario.path("prompt").asText(null);
                if (prompt != null) {
                    byPrompt.put(normalize(prompt), scenario);
                }
            }
            log.info("loaded {} mock scenarios", scenarios.size());
        } catch (Exception e) {
            log.warn("failed to load scenario fixtures: {}", e.toString());
        }
    }

    /** Find a scenario whose request prompt matches (case/whitespace-insensitive). */
    public Optional<JsonNode> findByPrompt(String prompt) {
        if (prompt == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byPrompt.get(normalize(prompt)));
    }

    /**
     * Find the judge response for the scenario whose primary AND shadow contents both appear in the
     * given judge user-message. This lets the mock judge return the mapped verdict without depending
     * on the exact prompt-message formatting.
     */
    public Optional<JsonNode> findJudgeRawResponse(String judgeUserMessage) {
        if (judgeUserMessage == null) {
            return Optional.empty();
        }
        for (JsonNode scenario : scenarios) {
            JsonNode judge = scenario.path("judge");
            JsonNode raw = judge.path("raw_response");
            String primary = scenario.path("primary_content").asText(null);
            String shadow = scenario.path("shadow_content").asText(null);
            if (judge.isMissingNode() || judge.isNull() || raw.isMissingNode()
                    || primary == null || shadow == null) {
                continue;
            }
            if (judgeUserMessage.contains(primary) && judgeUserMessage.contains(shadow)) {
                return Optional.of(raw);
            }
        }
        return Optional.empty();
    }

    public int size() {
        return scenarios.size();
    }

    private String normalize(String s) {
        return s.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
