package com.ftsm.rag.controller;

import com.ftsm.rag.service.ModelFactory;
import com.ftsm.rag.service.SettingsService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class VertexAiTestController {

    private final ModelFactory modelFactory;
    private final SettingsService settingsService;

    public VertexAiTestController(ModelFactory modelFactory, SettingsService settingsService) {
        this.modelFactory = modelFactory;
        this.settingsService = settingsService;
    }

    @GetMapping("/test-vertex")
    public Map<String, Object> testVertex() {
        String provider = settingsService.getLlmProvider();
        if (!"vertexai".equalsIgnoreCase(provider)) {
            return Map.of(
                    "status", "error",
                    "message", "LLM_PROVIDER is currently set to '" + provider + "', not 'vertexai'. Please update your .env file."
            );
        }

        try {
            log.info("Testing Vertex AI Gemini model...");
            ChatLanguageModel chatModel = modelFactory.getChatModel();
            
            long startTime = System.currentTimeMillis();
            String response = chatModel.generate("Hello, this is a Vertex AI test call from FTSM-RAG! Answer with a short greeting.");
            long duration = System.currentTimeMillis() - startTime;

            return Map.of(
                    "status", "success",
                    "provider", provider,
                    "model", settingsService.getVertexModelName(),
                    "project", settingsService.getVertexProjectId(),
                    "location", settingsService.getVertexLocation(),
                    "response", response.trim(),
                    "latencyMs", duration
            );
        } catch (Exception e) {
            log.error("Vertex AI test call failed", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Vertex AI test call failed: " + e.getMessage(), e);
        }
    }
}
