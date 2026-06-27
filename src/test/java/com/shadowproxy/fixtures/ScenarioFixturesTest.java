package com.shadowproxy.fixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowproxy.evaluation.CosineSimilarityService;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that the bundled mock data ({@code mockdata/llm-scenarios.json}) is internally
 * consistent: for every scenario that has both content strings, the documented routing
 * ({@code judge_invoked}) must agree with what the real {@link CosineSimilarityService} computes
 * against the scenario's threshold.
 *
 * <p>This keeps the fixtures honest — the example data can't drift away from how the system actually
 * behaves.
 */
class ScenarioFixturesTest {

    private final CosineSimilarityService similarity = new CosineSimilarityService();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode loadScenarios() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/mockdata/llm-scenarios.json")) {
            assertThat(in).as("fixtures file must be on the classpath").isNotNull();
            return mapper.readTree(in);
        }
    }

    @Test
    void fixturesFileLoadsAndHasScenarios() throws Exception {
        JsonNode root = loadScenarios();
        assertThat(root.path("scenarios").isArray()).isTrue();
        assertThat(root.path("scenarios")).isNotEmpty();
    }

    @TestFactory
    List<DynamicTest> similarityRoutingMatchesDocumentation() throws Exception {
        JsonNode root = loadScenarios();
        List<DynamicTest> tests = new ArrayList<>();

        for (JsonNode scenario : root.path("scenarios")) {
            JsonNode sim = scenario.path("similarity");
            JsonNode primary = scenario.path("primary_content");
            JsonNode shadow = scenario.path("shadow_content");

            // Skip error-path scenarios that have no comparison.
            if (sim.isMissingNode() || sim.isNull() || primary.isNull() || shadow.isNull()
                    || primary.isMissingNode() || shadow.isMissingNode()) {
                continue;
            }

            String id = scenario.path("id").asText();
            String primaryContent = primary.asText();
            String shadowContent = shadow.asText();
            double threshold = sim.path("threshold").asDouble();
            boolean judgeInvoked = sim.path("judge_invoked").asBoolean();

            tests.add(DynamicTest.dynamicTest(id, () -> {
                double score = similarity.similarity(primaryContent, shadowContent);
                if (judgeInvoked) {
                    assertThat(score)
                            .as("scenario '%s' is documented as inconclusive, so similarity must be below threshold", id)
                            .isLessThan(threshold);
                } else {
                    assertThat(score)
                            .as("scenario '%s' is documented as a similarity MATCH, so it must meet the threshold", id)
                            .isGreaterThanOrEqualTo(threshold);
                }
            }));
        }

        assertThat(tests).as("expected several comparison scenarios").isNotEmpty();
        return tests;
    }
}
