package com.shadowproxy.integration;

import com.shadowproxy.config.ProxyProperties;
import com.shadowproxy.metrics.MetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * End-to-end tests over real HTTP, driven by the bundled scenario fixtures.
 *
 * <p>Proves the engineering guarantees (primary stays fast and unaffected by a slow/failing shadow)
 * and the full pipeline (adapter → similarity → scenario-aware judge → metrics/logging) across two
 * different provider envelope shapes.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
class GenerateControllerIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ProxyProperties props;

    @Autowired
    MetricsService metrics;

    @BeforeEach
    void pointModelsAtLocalMocks() {
        String base = "http://localhost:" + port;
        props.getPrimary().setBaseUrl(base + "/mock/primary");
        props.getShadow().setBaseUrl(base + "/mock/shadow");
        // Use the scenario-aware mock judge over HTTP.
        props.getJudge().setProvider("openai");
        props.getJudge().setBaseUrl(base + "/mock/judge");
        props.getShadowMode().setEnabled(true);
    }

    @Test
    void primaryReturnsImmediatelyEvenWhenShadowIsSlow() {
        long start = System.currentTimeMillis();
        ResponseEntity<Map> response = rest.postForEntity(
                "/generate",
                Map.of("prompt", "[slow] what is recursion?"),
                Map.class);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("response").toString()).contains("42");
        // The shadow mock sleeps 1500ms; the primary must come back well before that.
        assertThat(elapsed).isLessThan(1000L);
    }

    @Test
    void identicalScenarioIsCountedAsMatchViaSimilarity() {
        long before = matches();

        ResponseEntity<Map> response = rest.postForEntity(
                "/generate",
                Map.of("prompt", "What is the capital of France?"),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("response").toString()).contains("Paris");

        waitUntil(() -> matches() > before, 5000);
        assertThat(matches()).isGreaterThan(before);
    }

    @Test
    void factualScenarioIsCountedAsMismatchViaJudge() {
        long before = mismatches();

        ResponseEntity<Map> response = rest.postForEntity(
                "/generate",
                Map.of("prompt", "What is the capital of Australia?"),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The user still gets the primary answer immediately.
        assertThat(response.getBody().get("response").toString()).contains("Canberra");

        waitUntil(() -> mismatches() > before, 5000);
        assertThat(mismatches()).isGreaterThan(before);
    }

    @Test
    void primaryFailureScenarioReturnsErrorAndSkipsShadow() {
        ResponseEntity<Map> response = rest.postForEntity(
                "/generate",
                Map.of("prompt", "Summarize the quarterly report."),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().get("error")).isEqualTo("PRIMARY_FAILED");
    }

    @Test
    void shadowFailureScenarioIsIsolatedFromPrimary() {
        long before = shadowFailures();

        ResponseEntity<Map> response = rest.postForEntity(
                "/generate",
                Map.of("prompt", "Give me today's weather."),
                Map.class);

        // Primary succeeds and is returned even though the shadow will fail.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("response").toString()).contains("sunny");

        waitUntil(() -> shadowFailures() > before, 5000);
        assertThat(shadowFailures()).isGreaterThan(before);
    }

    @Test
    void blankPromptIsRejected() {
        ResponseEntity<Map> response = rest.postForEntity(
                "/generate",
                Map.of("prompt", ""),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void metricsEndpointExposesCounters() {
        ResponseEntity<Map> response = rest.getForEntity("/metrics", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys(
                "total_requests", "matches", "mismatches", "match_rate", "mismatch_rate");
    }

    private long matches() {
        return ((Number) metrics.snapshot().get("matches")).longValue();
    }

    private long mismatches() {
        return ((Number) metrics.snapshot().get("mismatches")).longValue();
    }

    private long shadowFailures() {
        return ((Number) metrics.snapshot().get("shadow_failures")).longValue();
    }

    private void waitUntil(BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
