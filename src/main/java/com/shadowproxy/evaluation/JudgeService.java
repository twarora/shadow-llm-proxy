package com.shadowproxy.evaluation;

import com.shadowproxy.model.JudgeVerdict;

/**
 * Compares two answers (given the original prompt) and returns a structured verdict. Only invoked
 * when the cheap similarity pre-filter is inconclusive.
 */
public interface JudgeService {

    JudgeVerdict evaluate(String prompt, String primaryContent, String shadowContent);
}
