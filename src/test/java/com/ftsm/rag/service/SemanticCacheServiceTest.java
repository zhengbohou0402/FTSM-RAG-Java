package com.ftsm.rag.service;

import com.ftsm.rag.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class SemanticCacheServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsLegacyPythonEntriesWithoutNamespace() throws Exception {
        long now = Instant.now().getEpochSecond();
        Files.writeString(
                tempDir.resolve("semantic_cache.json"),
                """
                {
                  "entries": [
                    {
                      "question": "legacy question",
                      "answer": "legacy answer",
                      "vector": [0.1, 0.2],
                      "created_at": %d
                    }
                  ]
                }
                """.formatted(now),
                StandardCharsets.UTF_8
        );
        AppConfig config = new AppConfig();
        config.getQdrant().setDataPath(tempDir.toString());

        SemanticCacheService service = new SemanticCacheService(config, mock(ModelFactory.class));
        Map<String, Object> stats = service.stats();

        assertEquals(1, stats.get("size"));
        assertEquals(1, ((Map<?, ?>) stats.get("namespaces")).get("legacy"));
    }
}
