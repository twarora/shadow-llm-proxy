package com.shadowproxy.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowproxy.model.JudgeVerdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VerdictParserTest {

    private final VerdictParser parser = new VerdictParser(new ObjectMapper());

    @Test
    void parsesValidVerdict() {
        String json = """
                {"status":"MISMATCH","winner":"primary","summary":"differs",
                 "differences":[{"category":"factual","severity":"high","description":"42 vs 43"}]}
                """;
        JudgeVerdict verdict = parser.parse(json);

        assertThat(verdict.getStatus()).isEqualTo("MISMATCH");
        assertThat(verdict.getWinner()).isEqualTo("primary");
        assertThat(verdict.getDifferences()).hasSize(1);
        assertThat(verdict.getDifferences().get(0).getCategory()).isEqualTo("factual");
    }

    @Test
    void parsesVerdictWrappedInCodeFence() {
        String json = """
                ```json
                {"status":"MATCH","winner":"tie","summary":"same","differences":[]}
                ```
                """;
        JudgeVerdict verdict = parser.parse(json);
        assertThat(verdict.getStatus()).isEqualTo("MATCH");
    }

    @Test
    void invalidJsonReturnsUnknown() {
        JudgeVerdict verdict = parser.parse("the responses look the same to me");
        assertThat(verdict.getStatus()).isEqualTo("UNKNOWN");
    }

    @Test
    void emptyOutputReturnsUnknown() {
        assertThat(parser.parse("").getStatus()).isEqualTo("UNKNOWN");
        assertThat(parser.parse(null).getStatus()).isEqualTo("UNKNOWN");
    }

    @Test
    void missingStatusReturnsUnknown() {
        JudgeVerdict verdict = parser.parse("{\"winner\":\"tie\"}");
        assertThat(verdict.getStatus()).isEqualTo("UNKNOWN");
    }
}
