package com.shadowproxy.shadow;

import com.shadowproxy.adapter.ProviderAdapter;
import com.shadowproxy.config.ProxyProperties;
import com.shadowproxy.evaluation.EvaluationService;
import com.shadowproxy.llm.LlmClient;
import com.shadowproxy.logging.MismatchLogger;
import com.shadowproxy.metrics.MetricsService;
import com.shadowproxy.model.EvaluationResult;
import com.shadowproxy.model.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Fires and supervises the asynchronous shadow comparison.
 *
 * <p>Decoupling guarantee: the work is submitted to a dedicated {@link ExecutorService}, completely
 * separate from the servlet request thread. By the time this method returns, the primary response
 * has already been (or is about to be) sent to the client. Whatever happens to the shadow task
 * afterwards — slowness, exceptions, timeouts, or the client closing the connection — cannot affect
 * the primary response. Every failure mode is caught here and recorded as a shadow failure.
 */
@Service
public class ShadowService {

    private static final Logger log = LoggerFactory.getLogger(ShadowService.class);

    private final ExecutorService shadowExecutor;
    private final LlmClient llmClient;
    private final ProviderAdapter adapter;
    private final EvaluationService evaluationService;
    private final MetricsService metrics;
    private final MismatchLogger mismatchLogger;
    private final ProxyProperties props;

    public ShadowService(ExecutorService shadowExecutor,
                         LlmClient llmClient,
                         ProviderAdapter adapter,
                         EvaluationService evaluationService,
                         MetricsService metrics,
                         MismatchLogger mismatchLogger,
                         ProxyProperties props) {
        this.shadowExecutor = shadowExecutor;
        this.llmClient = llmClient;
        this.adapter = adapter;
        this.evaluationService = evaluationService;
        this.metrics = metrics;
        this.mismatchLogger = mismatchLogger;
        this.props = props;
    }

    /**
     * Schedule the shadow comparison. Returns immediately; never throws into the caller.
     */
    public void fireAndForget(String requestId, String prompt, String primaryContent) {
        if (!props.getShadowMode().isEnabled()) {
            return;
        }
        try {
            shadowExecutor.submit(() -> runShadow(requestId, prompt, primaryContent));
        } catch (RejectedExecutionException e) {
            // Queue is full — drop the shadow task. The primary response is unaffected.
            metrics.recordShadowFailure();
            log.warn("shadow task rejected (queue full) for request {}", requestId);
        }
    }

    private void runShadow(String requestId, String prompt, String primaryContent) {
        try {
            String rawShadow = llmClient.generate(props.getShadow(), prompt);
            String shadowContent = adapter.extractContent(props.getShadow().getProvider(), rawShadow);

            EvaluationResult result = evaluationService.evaluate(prompt, primaryContent, shadowContent);
            metrics.recordEvaluation(result.verdict());

            log.info("shadow evaluation request_id={} verdict={} source={} similarity={}",
                    requestId, result.verdict(), result.decisionSource(), result.similarity());

            if (result.verdict() == Verdict.MISMATCH) {
                mismatchLogger.log(requestId, prompt, primaryContent, shadowContent, result);
            }
        } catch (Exception e) {
            metrics.recordShadowFailure();
            log.warn("shadow task failed for request {}: {}", requestId, e.toString());
        }
    }
}
