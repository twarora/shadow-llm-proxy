package com.shadowproxy.controller;

import com.shadowproxy.adapter.ProviderAdapter;
import com.shadowproxy.config.ProxyProperties;
import com.shadowproxy.llm.LlmClient;
import com.shadowproxy.metrics.MetricsService;
import com.shadowproxy.model.ErrorResponse;
import com.shadowproxy.model.GenerateRequest;
import com.shadowproxy.model.GenerateResponse;
import com.shadowproxy.shadow.ShadowService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The proxy's main entry point.
 *
 * <p>Synchronous primary path: call the primary model, normalize via the adapter, and return the
 * content to the user immediately. Only after the primary response is ready do we fire the shadow
 * comparison as fire-and-forget work on a separate thread pool.
 */
@RestController
public class GenerateController {

    private static final Logger log = LoggerFactory.getLogger(GenerateController.class);

    private final ProxyProperties props;
    private final LlmClient llmClient;
    private final ProviderAdapter adapter;
    private final ShadowService shadowService;
    private final MetricsService metrics;

    public GenerateController(ProxyProperties props,
                              LlmClient llmClient,
                              ProviderAdapter adapter,
                              ShadowService shadowService,
                              MetricsService metrics) {
        this.props = props;
        this.llmClient = llmClient;
        this.adapter = adapter;
        this.shadowService = shadowService;
        this.metrics = metrics;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@Valid @RequestBody GenerateRequest request) {
        String requestId = (request.getRequestId() == null || request.getRequestId().isBlank())
                ? UUID.randomUUID().toString()
                : request.getRequestId();

        metrics.recordRequest();

        String primaryContent;
        try {
            String rawPrimary = llmClient.generate(props.getPrimary(), request.getPrompt());
            primaryContent = adapter.extractContent(props.getPrimary().getProvider(), rawPrimary);
        } catch (Exception e) {
            metrics.recordPrimaryFailure();
            log.warn("primary call failed for request {}: {}", requestId, e.toString());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new ErrorResponse("PRIMARY_FAILED", "Unable to generate response"));
        }

        // Fire the shadow comparison without awaiting it. This never blocks or throws into the
        // user-facing path.
        shadowService.fireAndForget(requestId, request.getPrompt(), primaryContent);

        return ResponseEntity.ok(new GenerateResponse(requestId, primaryContent));
    }
}
