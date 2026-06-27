package com.shadowproxy.evaluation;

import com.shadowproxy.adapter.ProviderAdapter;
import com.shadowproxy.config.ProxyProperties;
import com.shadowproxy.config.ProxyProperties.ModelConfig;
import com.shadowproxy.llm.LlmClient;
import com.shadowproxy.model.JudgeVerdict;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Judge implementation.
 *
 * <p>If {@code app.judge.provider == mock} the verdict is computed in-process (no network), which
 * keeps local runs and CI self-contained. Otherwise it calls the configured judge LLM, normalizes
 * the response through the {@link ProviderAdapter}, and parses the verdict JSON.
 */
@Service
public class DefaultJudgeService implements JudgeService {

    /** Evaluator system prompt — the judge's contract. */
    static final String SYSTEM_PROMPT = """
            You are an impartial evaluator comparing two responses to the same user prompt.

            Your task is to determine whether the responses are meaningfully equivalent.

            Do not compare wording alone. Focus on:
            - factual correctness
            - completeness
            - reasoning
            - safety
            - whether the user would receive the same information

            Ignore minor differences such as:
            - punctuation
            - formatting
            - synonyms
            - writing style
            - sentence order

            If the responses are meaningfully different:
            - determine which response is better
            - explain why
            - list the important differences

            Return ONLY valid JSON matching this schema.

            {
              "status": "MATCH | MISMATCH",
              "winner": "primary | shadow | tie",
              "summary": "Brief explanation",
              "differences": [
                {
                  "category": "factual | reasoning | completeness | safety | formatting | style",
                  "severity": "low | medium | high",
                  "description": "Describe the difference."
                }
              ]
            }

            Do not include markdown.
            Do not include code fences.
            Do not include any explanation outside the JSON.
            """;

    private final ProxyProperties props;
    private final LlmClient llmClient;
    private final ProviderAdapter adapter;
    private final VerdictParser verdictParser;

    public DefaultJudgeService(ProxyProperties props,
                               LlmClient llmClient,
                               ProviderAdapter adapter,
                               VerdictParser verdictParser) {
        this.props = props;
        this.llmClient = llmClient;
        this.adapter = adapter;
        this.verdictParser = verdictParser;
    }

    @Override
    public JudgeVerdict evaluate(String prompt, String primaryContent, String shadowContent) {
        ModelConfig cfg = props.getJudge();
        if ("mock".equalsIgnoreCase(cfg.getProvider())) {
            return mockVerdict(primaryContent, shadowContent);
        }
        String userMessage = buildUserMessage(prompt, primaryContent, shadowContent);
        String raw = llmClient.judge(cfg, SYSTEM_PROMPT, userMessage);
        String content = adapter.extractContent(cfg.getProvider(), raw);
        return verdictParser.parse(content);
    }

    /** In-process judge: equal-after-normalization → MATCH, otherwise MISMATCH. */
    private JudgeVerdict mockVerdict(String primary, String shadow) {
        String a = normalize(primary);
        String b = normalize(shadow);
        if (a.equals(b)) {
            return new JudgeVerdict("MATCH", "tie", "Normalized contents are identical.", List.of());
        }
        return new JudgeVerdict(
                "MISMATCH",
                "primary",
                "Contents differ beyond the similarity threshold.",
                List.of(new JudgeVerdict.Difference(
                        "factual", "high", "Primary and shadow answers are not equivalent.")));
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    static String buildUserMessage(String prompt, String primaryContent, String shadowContent) {
        return """
                User prompt:
                %s

                Response A (primary):
                %s

                Response B (shadow):
                %s
                """.formatted(prompt, primaryContent, shadowContent);
    }
}
