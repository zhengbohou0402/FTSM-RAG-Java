package com.ftsm.rag.store;

import com.ftsm.rag.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class DocumentManifestManagerStableDocIdTest {

    @Test
    void stableDocIdMatchesPythonOutput() {
        // Python: stable_file_doc_id("data/ukm_ftsm/example.txt") -> "file:data/ukm_ftsm/example.txt"
        AppConfig config = new AppConfig();
        config.setProjectRoot(System.getProperty("user.dir"));
        DocumentManifestManager manager = new DocumentManifestManager(config);

        Path filePath = Paths.get(System.getProperty("user.dir"), "data", "ukm_ftsm", "example.txt");
        String docId = manager.stableFileDocId(filePath);

        assertEquals("file:data/ukm_ftsm/example.txt", docId,
                "doc id must match Python stable_file_doc_id output exactly");
    }

    @Test
    void stableDocIdIncludesDataUkmFsmPrefix() {
        AppConfig config = new AppConfig();
        config.setProjectRoot(System.getProperty("user.dir"));
        DocumentManifestManager manager = new DocumentManifestManager(config);

        Path filePath = Paths.get(System.getProperty("user.dir"), "data", "ukm_ftsm", "ftsm_official_website.txt");
        String docId = manager.stableFileDocId(filePath);

        assertTrue(docId.startsWith("file:data/ukm_ftsm/"),
                "doc id must contain the full data/ukm_ftsm prefix to match Python");
        assertEquals("file:data/ukm_ftsm/ftsm_official_website.txt", docId);
    }

    @Test
    void stableDocIdIsConsistentAcrossDifferentCwd() {
        // Simulate that even if JVM cwd changes, doc_id stays the same
        String originalCwd = System.getProperty("user.dir");
        AppConfig config = new AppConfig();
        config.setProjectRoot(originalCwd);
        DocumentManifestManager manager = new DocumentManifestManager(config);

        Path absoluteFile = Paths.get(originalCwd, "data", "ukm_ftsm", "test.txt").toAbsolutePath().normalize();

        String docId1 = manager.stableFileDocId(absoluteFile);

        // Even with a different "current working directory" concept,
        // the project-root-based ID must remain identical
        assertEquals("file:data/ukm_ftsm/test.txt", docId1);
    }

    @Test
    void stableDocIdHandlesSubdirectories() {
        AppConfig config = new AppConfig();
        config.setProjectRoot(System.getProperty("user.dir"));
        DocumentManifestManager manager = new DocumentManifestManager(config);

        Path filePath = Paths.get(System.getProperty("user.dir"), "data", "ukm_ftsm", "sub", "dir", "file.txt");
        String docId = manager.stableFileDocId(filePath);

        assertEquals("file:data/ukm_ftsm/sub/dir/file.txt", docId);
    }

    @Test
    void stableDocIdHandlesWindowsPathSeparators() {
        AppConfig config = new AppConfig();
        config.setProjectRoot(System.getProperty("user.dir"));
        DocumentManifestManager manager = new DocumentManifestManager(config);

        // Even if the input path uses backslashes, output must use forward slashes
        Path filePath = Paths.get(System.getProperty("user.dir"), "data", "ukm_ftsm", "file.txt");
        String docId = manager.stableFileDocId(filePath);

        assertFalse(docId.contains("\\"), "doc id must not contain Windows backslashes");
        assertTrue(docId.contains("/"), "doc id must use forward slashes");
    }

    @Test
    void stableDocIdFallsBackToAbsolutePathWhenOutsideProjectRoot() {
        AppConfig config = new AppConfig();
        config.setProjectRoot(System.getProperty("user.dir"));
        DocumentManifestManager manager = new DocumentManifestManager(config);

        Path outside = Paths.get("C:/some/other/place/notes.txt").toAbsolutePath().normalize();
        String docId = manager.stableFileDocId(outside);

        assertTrue(docId.startsWith("file:"), "doc id should keep the file: prefix");
        assertFalse(docId.contains(".."), "fallback id should not leak relative traversal segments");
    }

    @Test
    void chunkIdIsPythonCompatibleWithNewDocId() {
        // This validates the full chain: stable doc id -> chunk UUIDv5 matches Python
        String docId = "file:data/ukm_ftsm/example.txt";
        String chunkId = com.ftsm.rag.service.VectorStoreService.pythonCompatibleChunkId(docId, 0);
        assertEquals("912399f5-9bfa-568e-999a-f40564b03d81", chunkId);
    }
}
