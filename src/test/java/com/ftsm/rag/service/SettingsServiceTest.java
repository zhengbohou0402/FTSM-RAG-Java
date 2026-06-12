package com.ftsm.rag.service;

import com.ftsm.rag.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsServiceTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearProperties() {
        System.clearProperty("DASHSCOPE_API_KEY");
        System.clearProperty("DASHSCOPE_BASE_URL");
        System.clearProperty("CHAT_MODEL_NAME");
    }

    @Test
    void savesSettingsAtomicallyAndPreservesUnrelatedLines() throws Exception {
        Path envPath = tempDir.resolve(".env");
        Files.writeString(
                envPath,
                "# retained comment\nUNRELATED=value\nDASHSCOPE_API_KEY=old-key\n",
                StandardCharsets.UTF_8
        );
        SettingsService service = new SettingsService(new AppConfig(), envPath);
        AtomicInteger notifications = new AtomicInteger();
        service.registerListener(notifications::incrementAndGet);

        service.saveSettings(
                "new-key",
                "https://dashscope.aliyuncs.com/api/v1",
                "qwen-plus"
        );

        String saved = Files.readString(envPath, StandardCharsets.UTF_8);
        assertTrue(saved.contains("# retained comment"));
        assertTrue(saved.contains("UNRELATED=value"));
        assertTrue(saved.contains("DASHSCOPE_API_KEY=new-key"));
        assertTrue(saved.contains("CHAT_MODEL_NAME=qwen-plus"));
        assertEquals("new-key", service.getApiKey());
        assertEquals(1, notifications.get());
        try (Stream<Path> files = Files.list(tempDir)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void failedPersistenceDoesNotPublishNewRuntimeSettings() throws Exception {
        Path invalidEnvPath = tempDir.resolve("env-directory");
        Files.createDirectory(invalidEnvPath);
        SettingsService service = new SettingsService(new AppConfig(), invalidEnvPath);

        assertThrows(
                IllegalStateException.class,
                () -> service.saveSettings("new-key", null, null)
        );
        assertEquals("", service.getApiKey());
    }
}
