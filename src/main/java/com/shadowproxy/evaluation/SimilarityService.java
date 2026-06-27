package com.shadowproxy.evaluation;

/**
 * Computes a similarity score in [0.0, 1.0] between two content strings. This is the cheap
 * pre-filter that runs before the (expensive) judge LLM.
 */
public interface SimilarityService {

    /**
     * @return similarity in the range [0.0, 1.0], where 1.0 means identical.
     */
    double similarity(String a, String b);
}
