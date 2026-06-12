package com.ftsm.rag.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data
public class CacheEntry {
    private String namespace = "";
    private String question;
    private String answer;
    private List<Double> vector;
    @JsonProperty("created_at")
    private long createdAt;
}
