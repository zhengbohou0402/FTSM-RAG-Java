package com.ftsm.rag.store;

import com.ftsm.rag.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsIdsThatWouldCollideAfterSanitizing() {
        AppConfig config = new AppConfig();
        config.getQdrant().setDataPath(tempDir.toString());
        ConversationStore store = new ConversationStore(config);
        String validId = "123e4567-e89b-12d3-a456-426614174000";

        assertNotNull(store.create(validId));
        assertThrows(IllegalArgumentException.class, () -> store.get("/" + validId));
        assertThrows(IllegalArgumentException.class, () -> store.delete("/" + validId));
        assertNotNull(store.get(validId));
        assertTrue(Files.isRegularFile(tempDir.resolve("conversations").resolve(validId + ".json")));
    }
}
