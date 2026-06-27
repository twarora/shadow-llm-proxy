package com.shadowproxy.evaluation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CosineSimilarityServiceTest {

    private final CosineSimilarityService similarity = new CosineSimilarityService();

    @Test
    void identicalStringsScoreOne() {
        assertThat(similarity.similarity("the answer is 42", "the answer is 42"))
                .isEqualTo(1.0);
    }

    @Test
    void reorderedAndRepunctuatedStringsAreHighlySimilar() {
        double score = similarity.similarity(
                "A function that calls itself.",
                "calls itself, a function that");
        assertThat(score).isGreaterThanOrEqualTo(0.90);
    }

    @Test
    void disjointStringsScoreLow() {
        double score = similarity.similarity(
                "the answer to the question is 42",
                "I am sorry but I cannot help with that");
        assertThat(score).isLessThan(0.90);
    }

    @Test
    void completelyDifferentStringsScoreZero() {
        assertThat(similarity.similarity("apple banana", "orange grape"))
                .isEqualTo(0.0);
    }

    @Test
    void emptyStringsAreHandled() {
        assertThat(similarity.similarity("", "")).isEqualTo(1.0);
        assertThat(similarity.similarity("something", "")).isEqualTo(0.0);
    }
}
