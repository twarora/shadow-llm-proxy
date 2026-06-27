package com.shadowproxy.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed verdict returned by the judge LLM. This is the ONLY model output that is ever parsed as
 * JSON in this system — the primary and shadow answers themselves are treated as opaque strings.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JudgeVerdict {

    private String status;
    private String winner;
    private String summary;
    private List<Difference> differences = new ArrayList<>();

    public JudgeVerdict() {
    }

    public JudgeVerdict(String status, String winner, String summary, List<Difference> differences) {
        this.status = status;
        this.winner = winner;
        this.summary = summary;
        this.differences = differences != null ? differences : new ArrayList<>();
    }

    public static JudgeVerdict unknown(String reason) {
        return new JudgeVerdict("UNKNOWN", "tie", reason, new ArrayList<>());
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getWinner() {
        return winner;
    }

    public void setWinner(String winner) {
        this.winner = winner;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<Difference> getDifferences() {
        return differences;
    }

    public void setDifferences(List<Difference> differences) {
        this.differences = differences != null ? differences : new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Difference {
        private String category;
        private String severity;
        private String description;

        public Difference() {
        }

        public Difference(String category, String severity, String description) {
            this.category = category;
            this.severity = severity;
            this.description = description;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public String getSeverity() {
            return severity;
        }

        public void setSeverity(String severity) {
            this.severity = severity;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
