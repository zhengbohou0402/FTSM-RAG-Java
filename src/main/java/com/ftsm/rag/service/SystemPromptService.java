package com.ftsm.rag.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Service
public class SystemPromptService {

    private static final int MAX_PROMPT_LENGTH = 20_000;

    private final Resource defaultPromptResource;
    private final Path promptPath;
    private volatile String cachedPrompt;

    @Autowired
    public SystemPromptService(
            @Value("classpath:prompts/main_prompt.txt") Resource defaultPromptResource) {
        this(defaultPromptResource, resolvePromptPath());
    }

    SystemPromptService(Resource defaultPromptResource, Path promptPath) {
        this.defaultPromptResource = defaultPromptResource;
        this.promptPath = promptPath.toAbsolutePath().normalize();
    }

    public String getPrompt() {
        String current = cachedPrompt;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cachedPrompt == null) {
                cachedPrompt = loadPrompt();
            }
            return cachedPrompt;
        }
    }

    public synchronized void savePrompt(String prompt) {
        String normalized = prompt == null ? "" : prompt.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("System prompt must not be empty");
        }
        if (normalized.length() > MAX_PROMPT_LENGTH) {
            throw new IllegalArgumentException("System prompt exceeds 20,000 characters");
        }

        try {
            Files.createDirectories(promptPath.getParent());
            Path tempPath = Files.createTempFile(promptPath.getParent(), ".system-prompt-", ".tmp");
            try {
                Files.writeString(tempPath, normalized, StandardCharsets.UTF_8);
                try {
                    Files.move(
                            tempPath,
                            promptPath,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE
                    );
                } catch (IOException atomicMoveError) {
                    Files.move(tempPath, promptPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tempPath);
            }
            cachedPrompt = normalized;
        } catch (IOException error) {
            throw new IllegalStateException("Failed to save system prompt", error);
        }
    }

    private String loadPrompt() {
        try {
            if (Files.isRegularFile(promptPath)) {
                String customPrompt = Files.readString(promptPath, StandardCharsets.UTF_8).trim();
                if (!customPrompt.isEmpty()) {
                    return customPrompt;
                }
            }
            return StreamUtils.copyToString(
                    defaultPromptResource.getInputStream(),
                    StandardCharsets.UTF_8
            ).trim();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load system prompt", error);
        }
    }

    private static Path resolvePromptPath() {
        String projectRoot = System.getenv("FTSM_PROJECT_ROOT");
        if (projectRoot != null && !projectRoot.isBlank()) {
            return Paths.get(projectRoot, "system_prompt.txt");
        }
        return Paths.get("system_prompt.txt");
    }
}
