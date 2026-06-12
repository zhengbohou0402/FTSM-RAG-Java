package com.ftsm.rag.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ConversationIndexItem {
    private String id;
    private String title = "New chat";
    @JsonProperty("updated_at")
    private long updatedAt;
}
