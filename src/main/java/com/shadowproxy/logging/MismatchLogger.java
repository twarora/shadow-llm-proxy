package com.shadowproxy.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shadowproxy.config.ProxyProperties;
import com.shadowproxy.model.EvaluationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes structured, machine-readable mismatch records. Each mismatch is emitted both to the SLF4J
 * log and (optionally) appended as one JSON object per line to a {@code .jsonl} file so it can be
 * queried later.
 */
@Component
public class MismatchLogger {

    private static final Logger log = LoggerFactory.getLogger("MISMATCH");

    private final ObjectMapper mapper;
    private final ProxyProperties props;

    public MismatchLogger(ObjectMapper mapper, ProxyProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    public void log(String requestId,
                    String prompt,
                    String primaryContent,
                    String shadowContent,
                    EvaluationResult result) {

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("request_id", requestId);
        record.put("timestamp", Instant.now().toString());
        record.put("prompt", prompt);
        record.put("primary_content", primaryContent);
        record.put("shadow_content", shadowContent);
        record.put("decision_source", result.decisionSource());
        record.put("similarity", result.similarity());
        record.put("evaluation", result.judgeVerdict());

        String json;
        try {
            json = mapper.writeValueAsString(record);
        } catch (Exception e) {
            log.warn("failed to serialize mismatch record for request {}", requestId, e);
            return;
        }

        log.warn("{}", json);

        if (props.getShadowMode().isLogMismatches()) {
            appendToFile(json);
        }
    }

    private void appendToFile(String json) {
        String path = props.getShadowMode().getMismatchLogPath();
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            Path file = Path.of(path);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(
                    file,
                    json + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("failed to append mismatch record to {}", path, e);
        }
    }
}
