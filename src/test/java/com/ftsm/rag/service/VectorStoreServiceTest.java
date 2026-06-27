package com.ftsm.rag.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorStoreServiceTest {

    @Test
    void fingerprintIsStableAndChangesWithPipelineConfiguration() {
        Map<String, Object> first = new TreeMap<>();
        first.put("embedding_model_name", "text-embedding-v3");
        first.put("chunk_size", 800);

        Map<String, Object> same = new TreeMap<>();
        same.put("chunk_size", 800);
        same.put("embedding_model_name", "text-embedding-v3");

        Map<String, Object> changed = new TreeMap<>(first);
        changed.put("chunk_size", 1000);

        assertEquals(
                VectorStoreService.buildIndexFingerprint(first),
                VectorStoreService.buildIndexFingerprint(same)
        );
        assertNotEquals(
                VectorStoreService.buildIndexFingerprint(first),
                VectorStoreService.buildIndexFingerprint(changed)
        );
    }

    @Test
    void tokenizerPreservesCourseCodesAndChineseBigrams() {
        var terms = VectorStoreService.tokenize("TC6244 高级机器学习");

        assertTrue(terms.contains("tc6244"));
        assertTrue(terms.contains("高级"));
        assertTrue(terms.contains("机器"));
        assertTrue(terms.contains("学习"));
    }
    @Test
    void chunkIdsMatchPythonUuid5() {
        assertEquals(
                "912399f5-9bfa-568e-999a-f40564b03d81",
                VectorStoreService.pythonCompatibleChunkId(
                        "file:data/ukm_ftsm/example.txt", 0)
        );
    }
}
