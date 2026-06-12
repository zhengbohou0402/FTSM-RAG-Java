package com.ftsm.rag.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class ConversationDetails {
    private String id;
    private String title = "New chat";
    @JsonProperty("updated_at")
    private long updatedAt;
    private List<ConversationMessage> messages = new ArrayList<>();
}
