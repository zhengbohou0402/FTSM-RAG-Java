package com.ftsm.rag.controller;

import com.ftsm.rag.service.SystemPromptService;
import com.ftsm.rag.service.SemanticCacheService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/prompt")
public class PromptController {

    private final SystemPromptService systemPromptService;
    private final SemanticCacheService semanticCacheService;

    public PromptController(SystemPromptService systemPromptService,
                            SemanticCacheService semanticCacheService) {
        this.systemPromptService = systemPromptService;
        this.semanticCacheService = semanticCacheService;
    }

    @GetMapping
    public Map<String, String> getPrompt() {
        return Map.of("system_prompt", systemPromptService.getPrompt());
    }

    @PostMapping
    public Map<String, Boolean> savePrompt(@RequestBody Map<String, String> payload) {
        try {
            systemPromptService.savePrompt(payload.get("system_prompt"));
            semanticCacheService.clear();
            return Map.of("ok", true);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
    }
}
