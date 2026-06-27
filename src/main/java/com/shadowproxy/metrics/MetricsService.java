package com.shadowproxy.metrics;

import com.shadowproxy.model.Verdict;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory, thread-safe counters powering the {@code GET /metrics} endpoint. Resets on restart;
 * a production system would back this with Redis or a metrics system.
 */
@Service
public class MetricsService {

    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong matches = new AtomicLong();
    private final AtomicLong mismatches = new AtomicLong();
    private final AtomicLong unknown = new AtomicLong();
    private final AtomicLong primaryFailures = new AtomicLong();
    private final AtomicLong shadowFailures = new AtomicLong();

    /** A request was accepted and the primary response was served. */
    public void recordRequest() {
        totalRequests.incrementAndGet();
    }

    public void recordPrimaryFailure() {
        primaryFailures.incrementAndGet();
    }

    public void recordShadowFailure() {
        shadowFailures.incrementAndGet();
    }

    public void recordEvaluation(Verdict verdict) {
        switch (verdict) {
            case MATCH -> matches.incrementAndGet();
            case MISMATCH -> mismatches.incrementAndGet();
            case UNKNOWN -> unknown.incrementAndGet();
        }
    }

    public Map<String, Object> snapshot() {
        long m = matches.get();
        long mm = mismatches.get();
        long u = unknown.get();
        long comparisons = m + mm + u;

        double matchRate = comparisons == 0 ? 0.0 : round((double) m / comparisons);
        double mismatchRate = comparisons == 0 ? 0.0 : round((double) mm / comparisons);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total_requests", totalRequests.get());
        out.put("comparisons", comparisons);
        out.put("matches", m);
        out.put("mismatches", mm);
        out.put("unknown", u);
        out.put("primary_failures", primaryFailures.get());
        out.put("shadow_failures", shadowFailures.get());
        out.put("match_rate", matchRate);
        out.put("mismatch_rate", mismatchRate);
        return out;
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
