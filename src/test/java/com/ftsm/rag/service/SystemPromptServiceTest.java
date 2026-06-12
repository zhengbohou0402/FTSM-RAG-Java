package com.ftsm.rag.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SystemPromptServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsDefaultAndPersistsCustomPrompt() throws Exception {
        Path promptPath = tempDir.resolve("system_prompt.txt");
        SystemPromptService service = new SystemPromptService(
                new ByteArrayResource("default prompt".getBytes(StandardCharsets.UTF_8)),
                promptPath
        );

        assertEquals("default prompt", service.getPrompt());
        service.savePrompt("custom prompt");

        assertEquals("custom prompt", service.getPrompt());
        assertEquals("custom prompt", Files.readString(promptPath, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsBlankPrompt() {
        SystemPromptService service = new SystemPromptService(
                new ByteArrayResource("default prompt".getBytes(StandardCharsets.UTF_8)),
                tempDir.resolve("system_prompt.txt")
        );

        assertThrows(IllegalArgumentException.class, () -> service.savePrompt("  "));
    }

    @Test
    void springCanConstructServiceWithItsResourceDependency() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(SystemPromptService.class);
            context.refresh();
            SystemPromptService service = context.getBean(SystemPromptService.class);
            assertEquals(false, service.getPrompt().isBlank());
        }
    }
}
