package com.shadowproxy.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.shadowproxy.mockdata.ScenarioStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Simulated LLM endpoints so the whole system runs end-to-end with no external API or keys.
 *
 * <p>Each mock first looks up the prompt in the {@link ScenarioStore}. If a scenario matches, the
 * mapped provider response (or error) is returned — so a real prompt like
 * {@code "What is the capital of Australia?"} deterministically drives the primary answer, the
 * shadow answer, and the judge verdict. If no scenario matches, the mocks fall back to simple
 * default behavior, with two control tokens for ad-hoc testing:
 * <ul>
 *   <li>{@code [mismatch]} → shadow returns a clearly different answer</li>
 *   <li>{@code [slow]}     → shadow sleeps, to prove the primary path is unaffected</li>
 * </ul>
 *
 * The envelopes intentionally differ by provider to exercise the adapter layer:
 * primary = OpenAI shape, shadow = Anthropic shape, judge = OpenAI shape (content is verdict JSON).
 */
@RestController
public class MockLlmController {

    private final ScenarioStore scenarios;

    public MockLlmController(ScenarioStore scenarios) {
        this.scenarios = scenarios;
    }

    @PostMapping("/mock/primary")
    public ResponseEntity<Object> primary(@RequestBody Map<String, Object> body) {
        String prompt = promptOf(body);

        Optional<JsonNode> scenario = scenarios.findByPrompt(prompt);
        if (scenario.isPresent()) {
            JsonNode s = scenario.get();
            JsonNode error = s.path("primary_error");
            if (!error.isMissingNode() && !error.isNull()) {
                int status = error.path("status").asInt(503);
                return ResponseEntity.status(status)
                        .body(Map.of("error", error.path("message").asText("primary failed")));
            }
            JsonNode response = s.path("primary_response");
            if (!response.isMissingNode() && !response.isNull()) {
                return ResponseEntity.ok(response);
            }
        }

        return ResponseEntity.ok(openAiEnvelope("mock-primary-v1", defaultAnswer(prompt)));
    }

    @PostMapping("/mock/shadow")
    public ResponseEntity<Object> shadow(@RequestBody Map<String, Object> body) throws InterruptedException {
        String prompt = promptOf(body);

        Optional<JsonNode> scenario = scenarios.findByPrompt(prompt);
        if (scenario.isPresent()) {
            JsonNode s = scenario.get();
            JsonNode error = s.path("shadow_error");
            if (!error.isMissingNode() && !error.isNull()) {
                // Simulate a failing/timing-out shadow with a 5xx so the client throws and the
                // shadow worker records a shadow_failure.
                return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                        .body(Map.of("error", error.path("message").asText("shadow failed")));
            }
            JsonNode response = s.path("shadow_response");
            if (!response.isMissingNode() && !response.isNull()) {
                return ResponseEntity.ok(response);
            }
        }

        // Fallback default behavior with control tokens.
        if (prompt.toLowerCase().contains("[slow]")) {
            Thread.sleep(1500);
        }
        String content = prompt.toLowerCase().contains("[mismatch]")
                ? "I'm sorry, but I can't help with that request."
                : defaultAnswer(prompt);
        return ResponseEntity.ok(anthropicEnvelope("mock-shadow-v1", content));
    }

    @PostMapping("/mock/judge")
    public ResponseEntity<Object> judge(@RequestBody Map<String, Object> body) {
        String userMessage = String.valueOf(body.getOrDefault("prompt", ""));

        Optional<JsonNode> mapped = scenarios.findJudgeRawResponse(userMessage);
        if (mapped.isPresent()) {
            return ResponseEntity.ok(mapped.get());
        }

        // Fallback verdict for prompts that are not in the fixtures.
        String verdict = """
                {"status":"MISMATCH","winner":"primary","summary":"No scenario mapping; default mismatch.",\
                "differences":[{"category":"factual","severity":"high","description":"answers differ"}]}""";
        return ResponseEntity.ok(openAiEnvelope("mock-judge", verdict));
    }

    private String defaultAnswer(String prompt) {
        return "The answer to \"" + prompt + "\" is 42.";
    }

    private Map<String, Object> openAiEnvelope(String model, String content) {
        return Map.of(
                "id", "mock-" + model,
                "model", model,
                "choices", List.of(Map.of(
                        "index", 0,
                        "message", Map.of("role", "assistant", "content", content),
                        "finish_reason", "stop")));
    }

    private Map<String, Object> anthropicEnvelope(String model, String content) {
        return Map.of(
                "id", "mock-" + model,
                "model", model,
                "content", List.of(Map.of("type", "text", "text", content)));
    }

    private String promptOf(Map<String, Object> body) {
        Object p = body.get("prompt");
        return p == null ? "" : p.toString();
    }
}
