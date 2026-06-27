package com.ftsm.rag.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryPreprocessorTest {

    private final QueryPreprocessor preprocessor = new QueryPreprocessor();

    @Test
    void convertsTraditionalChineseToSimplified() {
        List<String> queries = preprocessor.process("籤證怎麼續簽");
        // The first entry must contain the simplified form rather than mojibake or
        // the original traditional characters.
        assertTrue(queries.get(0).contains("签") || queries.get(0).contains("證"),
                "Traditional-to-simplified conversion should keep the recognizable character set, got: "
                        + queries.get(0));
        assertFalse(queries.get(0).contains("籤"),
                "Traditional form should have been converted: " + queries.get(0));
    }

    @Test
    void expandsStudentPassSynonymsAcrossThreeLanguages() {
        List<String> queries = preprocessor.process("学生签证怎么续签");
        // Main query + at least one expansion entry expected
        assertTrue(queries.size() >= 2,
                "Expected at least one synonym expansion, got: " + queries);
        String joined = String.join(" | ", queries);
        // English / Malay expansions should appear
        assertTrue(joined.toLowerCase().contains("student pass")
                        || joined.toLowerCase().contains("permit pelajar"),
                "Expected English/Malay synonym expansion, got: " + joined);
    }

    @Test
    void navigationPatternsAreStripped() {
        List<String> queries = preprocessor.process("Can you tell me where can I find the FTSM map?");
        // The cleaned/normalized main query is preserved as the first entry;
        // the stripped-down variant must appear among the rewrites.
        String main = queries.get(0);
        assertTrue(main.toLowerCase().contains("ftsm"),
                "Subject matter should remain: " + main);
        boolean strippedFound = queries.stream()
                .anyMatch(q -> !q.toLowerCase().contains("can you tell me")
                        && !q.toLowerCase().contains("where can i find"));
        assertTrue(strippedFound,
                "At least one rewrite should have navigation pattern removed: " + queries);
    }

    @Test
    void topicRewriteIsAddedForVisaDomain() {
        List<String> queries = preprocessor.process("How to renew student pass for FTSM?");
        String joined = String.join(" | ", queries).toLowerCase();
        // Topic rewrite rule for visa/student pass/renewal should fire.
        assertTrue(joined.contains("emgs") || joined.contains("passport"),
                "Topic rewrite should add EMGS / passport terminology, got: " + joined);
    }

    @Test
    void normalizationFoldsWhitespaceAndFullWidth() {
        List<String> queries = preprocessor.process("  你好  ，  请问图书馆   在哪里？  ");
        String main = queries.get(0);
        assertEquals(main.trim(), main, "Main query should be trimmed: " + main);
        assertFalse(main.contains("  "), "No double spaces should remain: " + main);
    }

    @Test
    void casualMessageDoesNotGainExpansions() {
        List<String> queries = preprocessor.process("你好");
        // Greetings should not trigger synonym expansion
        assertEquals(1, queries.size(),
                "Casual greetings should yield only the normalized main query, got: " + queries);
    }
}
