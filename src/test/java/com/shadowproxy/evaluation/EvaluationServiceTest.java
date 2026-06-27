package com.shadowproxy.evaluation;

import com.shadowproxy.config.ProxyProperties;
import com.shadowproxy.model.EvaluationResult;
import com.shadowproxy.model.JudgeVerdict;
import com.shadowproxy.model.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationServiceTest {

    private ProxyProperties propsWithThreshold(double threshold) {
        ProxyProperties props = new ProxyProperties();
        props.getEvaluation().setSimilarityThreshold(threshold);
        return props;
    }

    @Test
    void highSimilarityMatchesAndSkipsJudge() {
        AtomicInteger judgeCalls = new AtomicInteger();
        SimilarityService sim = (a, b) -> 0.97;
        JudgeService judge = (p, a, b) -> {
            judgeCalls.incrementAndGet();
            return new JudgeVerdict("MISMATCH", "primary", "should not be called", List.of());
        };

        EvaluationService service = new EvaluationService(propsWithThreshold(0.90), sim, judge);
        EvaluationResult result = service.evaluate("prompt", "a", "b");

        assertThat(result.verdict()).isEqualTo(Verdict.MATCH);
        assertThat(result.decisionSource()).isEqualTo("semantic_similarity");
        assertThat(judgeCalls.get()).isZero();
    }

    @Test
    void lowSimilarityFallsThroughToJudgeMismatch() {
        SimilarityService sim = (a, b) -> 0.10;
        JudgeService judge = (p, a, b) ->
                new JudgeVerdict("MISMATCH", "primary", "differs", List.of());

        EvaluationService service = new EvaluationService(propsWithThreshold(0.90), sim, judge);
        EvaluationResult result = service.evaluate("prompt", "a", "b");

        assertThat(result.verdict()).isEqualTo(Verdict.MISMATCH);
        assertThat(result.decisionSource()).isEqualTo("judge_llm");
        assertThat(result.judgeVerdict()).isNotNull();
    }

    @Test
    void lowSimilarityWithJudgeMatch() {
        SimilarityService sim = (a, b) -> 0.50;
        JudgeService judge = (p, a, b) ->
                new JudgeVerdict("MATCH", "tie", "equivalent", List.of());

        EvaluationService service = new EvaluationService(propsWithThreshold(0.90), sim, judge);
        EvaluationResult result = service.evaluate("prompt", "a", "b");

        assertThat(result.verdict()).isEqualTo(Verdict.MATCH);
        assertThat(result.decisionSource()).isEqualTo("judge_llm");
    }

    @Test
    void thresholdBoundaryIsInclusive() {
        SimilarityService sim = (a, b) -> 0.90;
        JudgeService judge = (p, a, b) -> {
            throw new AssertionError("judge must not be called at the threshold");
        };

        EvaluationService service = new EvaluationService(propsWithThreshold(0.90), sim, judge);
        assertThat(service.evaluate("p", "a", "b").verdict()).isEqualTo(Verdict.MATCH);
    }

    @Test
    void unrecognizedJudgeStatusBecomesUnknown() {
        SimilarityService sim = (a, b) -> 0.10;
        JudgeService judge = (p, a, b) -> new JudgeVerdict("???", "tie", "huh", List.of());

        EvaluationService service = new EvaluationService(propsWithThreshold(0.90), sim, judge);
        assertThat(service.evaluate("p", "a", "b").verdict()).isEqualTo(Verdict.UNKNOWN);
    }
}
