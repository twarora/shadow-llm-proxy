package com.shadowproxy.model;

/**
 * Outcome of comparing the primary and shadow content strings.
 *
 * @param verdict        MATCH / MISMATCH / UNKNOWN
 * @param decisionSource which stage decided: {@code semantic_similarity} or {@code judge_llm}
 * @param similarity     the computed similarity score (always present)
 * @param judgeVerdict   the judge's structured verdict, or {@code null} if the judge was skipped
 */
public record EvaluationResult(
        Verdict verdict,
        String decisionSource,
        double similarity,
        JudgeVerdict judgeVerdict) {

    public static EvaluationResult bySimilarity(double similarity) {
        return new EvaluationResult(Verdict.MATCH, "semantic_similarity", similarity, null);
    }

    public static EvaluationResult byJudge(Verdict verdict, double similarity, JudgeVerdict judgeVerdict) {
        return new EvaluationResult(verdict, "judge_llm", similarity, judgeVerdict);
    }
}
