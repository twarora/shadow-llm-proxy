package com.shadowproxy.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowproxy.model.JudgeVerdict;
import org.springframework.stereotype.Component;

/**
 * Parses the judge LLM's textual output into a {@link JudgeVerdict}.
 *
 * <p>The judge is instructed to return ONLY JSON, but real models occasionally wrap it in a
 * markdown code fence, so we defensively strip fences before parsing. This is the only place in the
 * system that parses model output as JSON — and it parses the judge's verdict, not the answers.
 */
@Component
public class VerdictParser {

    private final ObjectMapper mapper;

    public VerdictParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JudgeVerdict parse(String judgeOutput) {
        if (judgeOutput == null || judgeOutput.isBlank()) {
            return JudgeVerdict.unknown("judge returned empty output");
        }
        String cleaned = stripCodeFences(judgeOutput.trim());
        try {
            JudgeVerdict verdict = mapper.readValue(cleaned, JudgeVerdict.class);
            if (verdict.getStatus() == null || verdict.getStatus().isBlank()) {
                return JudgeVerdict.unknown("judge verdict missing 'status'");
            }
            return verdict;
        } catch (Exception e) {
            return JudgeVerdict.unknown("could not parse judge verdict: " + e.getMessage());
        }
    }

    private String stripCodeFences(String text) {
        String t = text;
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline >= 0) {
                t = t.substring(firstNewline + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.trim();
    }
}
