package com.ftsm.rag.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class ChatRequest {
    private String message;
    @JsonProperty("conversation_id")
    private String conversationId;
}
