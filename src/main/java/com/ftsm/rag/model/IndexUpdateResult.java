package com.ftsm.rag.model;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * Structured result of an incremental index update operation.
 */
@Data
@Builder(toBuilder = true)
public class IndexUpdateResult {
    private boolean success;
    private boolean modified;
    private boolean consistent;
    private List<String> errors;
    private String errorSummary;
    private int documentCount;
    private long totalChunks;
    private String qdrantCollection;
    private String lexicalGeneration;
    private int newVersion;

    public static IndexUpdateResult unchanged() {
        return IndexUpdateResult.builder()
                .success(true)
                .modified(false)
                .consistent(true)
                .errors(Collections.emptyList())
                .errorSummary(null)
                .build();
    }

    public static IndexUpdateResult failed(List<String> errors, boolean consistent) {
        return IndexUpdateResult.builder()
                .success(false)
                .modified(false)
                .consistent(consistent)
                .errors(errors == null ? Collections.emptyList() : errors)
                .errorSummary(errors == null || errors.isEmpty() ? null : String.join("; ", errors))
                .build();
    }
}
