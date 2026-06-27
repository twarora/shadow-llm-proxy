package com.shadowproxy.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderAdapterTest {

    private final ProviderAdapter adapter = new ProviderAdapter(new ObjectMapper());

    @Test
    void extractsOpenAiContent() {
        String raw = """
                {
                  "choices": [
                    { "message": { "role": "assistant", "content": "Recursion is when a function calls itself." } }
                  ]
                }
                """;
        assertThat(adapter.extractContent("openai", raw))
                .isEqualTo("Recursion is when a function calls itself.");
    }

    @Test
    void extractsAnthropicContent() {
        String raw = """
                { "content": [ { "type": "text", "text": "A function that calls itself." } ] }
                """;
        assertThat(adapter.extractContent("anthropic", raw))
                .isEqualTo("A function that calls itself.");
    }

    @Test
    void extractsMockContent() {
        String raw = "{ \"output\": \"hello world\" }";
        assertThat(adapter.extractContent("mock", raw)).isEqualTo("hello world");
    }

    @Test
    void isCaseInsensitiveOnProvider() {
        String raw = "{ \"output\": \"x\" }";
        assertThat(adapter.extractContent("MOCK", raw)).isEqualTo("x");
    }

    @Test
    void unknownProviderThrows() {
        assertThatThrownBy(() -> adapter.extractContent("cohere", "{}"))
                .isInstanceOf(AdapterException.class)
                .hasMessageContaining("unknown provider");
    }

    @Test
    void missingContentFieldThrows() {
        String raw = "{ \"choices\": [ { \"message\": {} } ] }";
        assertThatThrownBy(() -> adapter.extractContent("openai", raw))
                .isInstanceOf(AdapterException.class)
                .hasMessageContaining("could not find content");
    }

    @Test
    void invalidJsonThrows() {
        assertThatThrownBy(() -> adapter.extractContent("openai", "not json"))
                .isInstanceOf(AdapterException.class)
                .hasMessageContaining("not valid JSON");
    }
}
