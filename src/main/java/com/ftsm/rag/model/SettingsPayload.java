package com.ftsm.rag.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class SettingsPayload {
    @JsonProperty("dashscope_api_key")
    private String dashscopeApiKey = "";
    @JsonProperty("dashscope_base_url")
    private String dashscopeBaseUrl = "";
    @JsonProperty("chat_model_name")
    private String chatModelName = "";
}
