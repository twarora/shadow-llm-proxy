package com.shadowproxy.evaluation;

import com.shadowproxy.config.ProxyProperties;
import com.shadowproxy.model.EvaluationResult;
import com.shadowproxy.model.JudgeVerdict;
import com.shadowproxy.model.Verdict;
import org.springframework.stereotype.Service;

/**
 * Two-stage evaluation, cheapest first:
 *
 * <ol>
 *   <li>Stage 1 — semantic similarity. If similarity ≥ threshold, return MATCH and skip the judge.</li>
 *   <li>Stage 2 — judge LLM. Only for inconclusive cases; its verdict decides MATCH/MISMATCH.</li>
 * </ol>
 *
 * Both stages operate on the raw content strings (plus the prompt for the judge). The answers
 * themselves are never parsed as JSON.
 */
@Service
public class EvaluationService {

    private final ProxyProperties props;
    private final SimilarityService similarityService;
    private final JudgeService judgeService;

    public EvaluationService(ProxyProperties props,
                             SimilarityService similarityService,
                             JudgeService judgeService) {
        this.props = props;
        this.similarityService = similarityService;
        this.judgeService = judgeService;
    }

    public EvaluationResult evaluate(String prompt, String primaryContent, String shadowContent) {
        double similarity = similarityService.similarity(primaryContent, shadowContent);
        double threshold = props.getEvaluation().getSimilarityThreshold();

        if (similarity >= threshold) {
            return EvaluationResult.bySimilarity(similarity);
        }

        JudgeVerdict verdict = judgeService.evaluate(prompt, primaryContent, shadowContent);
        return EvaluationResult.byJudge(toVerdict(verdict.getStatus()), similarity, verdict);
    }

    private Verdict toVerdict(String status) {
        if (status == null) {
            return Verdict.UNKNOWN;
        }
        return switch (status.trim().toUpperCase()) {
            case "MATCH" -> Verdict.MATCH;
            case "MISMATCH" -> Verdict.MISMATCH;
            default -> Verdict.UNKNOWN;
        };
    }
}
