package com.ftsm.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
@Service
public class TraceLogger {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentLinkedDeque<Map<String, Object>> recentTraces = new ConcurrentLinkedDeque<>();
    private static final int MAX_TRACES = 1000;
    
    // Use ThreadLocal to pass traceId around synchronous or bounded-elastic reactor streams
    private static final ThreadLocal<String> currentTraceId = new ThreadLocal<>();

    public static String startTrace() {
        String traceId = UUID.randomUUID().toString();
        currentTraceId.set(traceId);
        return traceId;
    }
    
    public static void setTraceId(String traceId) {
        currentTraceId.set(traceId);
    }

    public static String getTraceId() {
        return currentTraceId.get();
    }
    
    public static void clearTrace() {
        currentTraceId.remove();
    }

    public void logStage(String stageName, long durationMs, String inputSummary, String outputSummary) {
        logStage(stageName, durationMs, inputSummary, outputSummary, (String[]) null);
    }

    public void logStage(String stageName, long durationMs, String inputSummary, String outputSummary, String... extraDetails) {
        String traceId = getTraceId();
        if (traceId == null) {
            traceId = startTrace();
        }

        try {
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("trace_id", traceId);
            trace.put("timestamp", Instant.now().toString());
            trace.put("stage", stageName);
            trace.put("duration_ms", durationMs);
            trace.put("input_summary", inputSummary);
            trace.put("output_summary", outputSummary);
            if (extraDetails != null) {
                for (String detail : extraDetails) {
                    int eq = detail != null ? detail.indexOf('=') : -1;
                    if (eq > 0) {
                        trace.put(detail.substring(0, eq), detail.substring(eq + 1));
                    }
                }
            }

            // Log to stdout
            log.info("RAG_TRACE: {}", objectMapper.writeValueAsString(trace));

            // Add to memory queue
            recentTraces.addFirst(trace);
            while (recentTraces.size() > MAX_TRACES) {
                recentTraces.removeLast();
            }
        } catch (Exception e) {
            log.warn("Failed to write trace log: {}", e.getMessage());
        }
    }
    
    public List<Map<String, Object>> getRecentTraces(int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        int count = 0;
        for (Map<String, Object> trace : recentTraces) {
            result.add(trace);
            if (++count >= limit) break;
        }
        return result;
    }

    public void logTrace(String query, String rawContext, String compressedContext, long durationMs) {
        logStage("compression", durationMs, 
                "raw_len=" + (rawContext != null ? rawContext.length() : 0), 
                "compressed_len=" + (compressedContext != null ? compressedContext.length() : 0) + ", ratio=" + calculateRatio(rawContext, compressedContext));
    }

    private double calculateRatio(String raw, String compressed) {
        if (raw == null || raw.isEmpty()) return 0.0;
        if (compressed == null) return 0.0;
        return (double) compressed.length() / raw.length();
    }
}
