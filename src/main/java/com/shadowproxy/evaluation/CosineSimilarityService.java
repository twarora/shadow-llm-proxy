package com.shadowproxy.evaluation;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Default similarity implementation: cosine similarity over bag-of-words term-frequency vectors.
 *
 * <p>This is deterministic and runs entirely offline, which keeps tests and CI green without any
 * API keys. It is a stand-in for true semantic embeddings: in production you would implement
 * {@link SimilarityService} by calling an embedding model (configured via
 * {@code app.evaluation.embedding-*}) and computing cosine similarity over the returned vectors.
 * The rest of the pipeline does not change when you swap the implementation.
 */
@Component
public class CosineSimilarityService implements SimilarityService {

    @Override
    public double similarity(String a, String b) {
        String left = a == null ? "" : a;
        String right = b == null ? "" : b;

        if (left.equals(right)) {
            // Identical strings (the common "both models agree exactly" case) → perfect score.
            return left.isEmpty() ? 1.0 : 1.0;
        }

        Map<String, Integer> va = termFrequencies(left);
        Map<String, Integer> vb = termFrequencies(right);
        if (va.isEmpty() || vb.isEmpty()) {
            return 0.0;
        }

        Set<String> terms = new HashSet<>();
        terms.addAll(va.keySet());
        terms.addAll(vb.keySet());

        double dot = 0.0;
        for (String term : terms) {
            dot += va.getOrDefault(term, 0) * (double) vb.getOrDefault(term, 0);
        }

        double magA = magnitude(va);
        double magB = magnitude(vb);
        if (magA == 0.0 || magB == 0.0) {
            return 0.0;
        }
        return dot / (magA * magB);
    }

    private Map<String, Integer> termFrequencies(String text) {
        Map<String, Integer> freq = new HashMap<>();
        for (String token : tokenize(text)) {
            freq.merge(token, 1, Integer::sum);
        }
        return freq;
    }

    private String[] tokenize(String text) {
        String normalized = text.toLowerCase().trim();
        if (normalized.isEmpty()) {
            return new String[0];
        }
        // Split on any run of non-alphanumeric characters.
        return normalized.split("[^a-z0-9]+");
    }

    private double magnitude(Map<String, Integer> vector) {
        double sum = 0.0;
        for (int count : vector.values()) {
            sum += (double) count * count;
        }
        return Math.sqrt(sum);
    }
}
