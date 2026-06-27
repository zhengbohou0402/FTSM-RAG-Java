package com.ftsm.rag.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.HashMap;
import java.util.Map;

@Data
public class IndexState {
    private int version = 0;
    @JsonProperty("updated_at")
    @JsonAlias("updatedAt")
    private String updatedAt;
    @JsonProperty("last_indexed")
    @JsonAlias("lastIndexed")
    private String lastIndexed;
    @JsonProperty("document_count")
    @JsonAlias("documentCount")
    private int documentCount = 0;
    @JsonProperty("total_chunks")
    @JsonAlias("totalChunks")
    private int totalChunks = 0;
    @JsonProperty("source_type_counts")
    @JsonAlias("sourceTypeCounts")
    private Map<String, Integer> sourceTypeCounts = new HashMap<>();
    @JsonProperty("last_error")
    @JsonAlias("lastError")
    private String lastError;
    @JsonProperty("pipeline_fingerprint")
    @JsonAlias("pipelineFingerprint")
    private String pipelineFingerprint;
    @JsonProperty("qdrant_collection")
    @JsonAlias("qdrantCollection")
    private String qdrantCollection;
    @JsonProperty("lexical_generation")
    @JsonAlias("lexicalGeneration")
    private String lexicalGeneration;
    @JsonProperty("schema_version")
    @JsonAlias("schemaVersion")
    private int schemaVersion = 0;
}
