package com.ftsm.rag.store;

import com.ftsm.rag.config.AppConfig;
import com.ftsm.rag.model.ManifestData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentManifestManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void readsPythonSnakeCaseAndWritesCompatibleJson() throws Exception {
        AppConfig config = new AppConfig();
        config.getQdrant().setDataPath(tempDir.toString());
        Path manifestPath = tempDir.resolve("ingestion_manifest.json");
        Files.writeString(manifestPath, """
                {
                  "documents": {
                    "file:data/example.txt": {
                      "doc_id": "file:data/example.txt",
                      "source_type": "official",
                      "source_url": null,
                      "title": "example",
                      "file_path": "data/example.txt",
                      "updated_at": "2026-06-11T00:00:00Z",
                      "hash": "abc",
                      "permission_scope": "public",
                      "extra": {"filename": "example.txt"},
                      "chunk_ids": ["00000000-0000-0000-0000-000000000001"],
                      "index_fingerprint": "python-index",
                      "index_config": {"schema_version": 2},
                      "indexed_at": "2026-06-11T00:00:00Z"
                    }
                  },
                  "index": {
                    "version": 5,
                    "updated_at": "2026-06-11T00:00:00Z",
                    "document_count": 1,
                    "total_chunks": 1,
                    "source_type_counts": {"official": 1},
                    "last_error": null,
                    "pipeline_fingerprint": "python-index"
                  }
                }
                """, StandardCharsets.UTF_8);

        DocumentManifestManager manager = new DocumentManifestManager(config);
        ManifestData manifest = manager.loadManifest();

        assertEquals(1, manifest.getDocuments().size());
        assertEquals("official", manifest.getDocuments().values().iterator().next().getSourceType());
        assertEquals(5, manifest.getIndex().getVersion());

        manager.saveManifest(manifest);
        String saved = Files.readString(manifestPath, StandardCharsets.UTF_8);
        assertTrue(saved.contains("\"doc_id\""));
        assertTrue(saved.contains("\"chunk_ids\""));
        assertTrue(saved.contains("\"pipeline_fingerprint\""));
        assertFalse(saved.contains("\"docId\""));
    }
}
