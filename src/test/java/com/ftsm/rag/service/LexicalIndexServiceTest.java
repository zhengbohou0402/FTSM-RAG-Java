package com.ftsm.rag.service;

import com.ftsm.rag.config.AppConfig;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LexicalIndexServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void searchesCourseCodesAndReplacesDocumentsIncrementally() {
        AppConfig config = new AppConfig();
        config.getQdrant().setLexicalIndexPath(tempDir.toString());
        LexicalIndexService index = new LexicalIndexService(config);

        index.rebuild("g-1", List.of(
                document("chunk-1", "doc-1", "TC6244 Advanced Machine Learning"),
                document("chunk-2", "doc-2", "Campus bus route information")
        ));

        assertEquals("chunk-1",
                index.search("g-1", "TC6244", 5).get(0).metadata().getString("chunk_id"));
        assertEquals(2, index.count("g-1"));

        index.replaceDocument("g-1", "doc-1", List.of(
                document("chunk-3", "doc-1", "TK6123 Data Mining")
        ));

        assertEquals(0, index.search("g-1", "TC6244", 5).size());
        assertEquals("chunk-3",
                index.search("g-1", "TK6123", 5).get(0).metadata().getString("chunk_id"));
    }

    private Document document(String chunkId, String docId, String text) {
        Metadata metadata = new Metadata();
        metadata.put("chunk_id", chunkId);
        metadata.put("doc_id", docId);
        metadata.put("chunk_index", "0");
        return Document.from(text, metadata);
    }
}
