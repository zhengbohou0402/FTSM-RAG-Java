package com.ftsm.rag.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class ManifestRecord {
    @JsonProperty("doc_id")
    @JsonAlias("docId")
    private String docId;
    @JsonProperty("source_type")
    @JsonAlias("sourceType")
    private String sourceType;
    @JsonProperty("source_url")
    @JsonAlias("sourceUrl")
    private String sourceUrl;
    private String title;
    @JsonProperty("file_path")
    @JsonAlias("filePath")
    private String filePath;
    @JsonProperty("updated_at")
    @JsonAlias("updatedAt")
    private String updatedAt;
    private String hash;
    @JsonProperty("permission_scope")
    @JsonAlias("permissionScope")
    private String permissionScope;
    private Map<String, Object> extra = new HashMap<>();
    @JsonProperty("chunk_ids")
    @JsonAlias("chunkIds")
    private List<String> chunkIds = new ArrayList<>();
    @JsonProperty("index_fingerprint")
    @JsonAlias("indexFingerprint")
    private String indexFingerprint;
    @JsonProperty("index_config")
    @JsonAlias("indexConfig")
    private Map<String, Object> indexConfig = new HashMap<>();
    @JsonProperty("indexed_at")
    @JsonAlias("indexedAt")
    private String indexedAt;
}
