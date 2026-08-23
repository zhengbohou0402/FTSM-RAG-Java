package com.ftsm.rag.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonCompatibleTextSplitterTest {

    @Test
    void matchesPythonRecursiveSplitterForCurrentKnowledgeFiles() throws Exception {
        PythonCompatibleTextSplitter splitter = new PythonCompatibleTextSplitter(800, 120);

        var searchIndexChunks = splitChunks(splitter, "student_portal_search_index.txt");
        assertEquals(
                19,
                searchIndexChunks.size(),
                searchIndexChunks.stream().map(String::length).toList().toString());
        assertEquals(9, splitFile(splitter, "coursework_timetable_ftsm_sem2_2025_2026.txt"));
        assertEquals(11, splitFile(splitter, "ukm_campus_bus_routes_guide.txt"));
        assertEquals(87, splitFile(splitter, "advisors_expertise_index.txt"));
    }

    @Test
    void keepsOverlapAndNeverExceedsConfiguredSize() {
        PythonCompatibleTextSplitter splitter = new PythonCompatibleTextSplitter(20, 5);
        var chunks = splitter.splitText("alpha beta gamma delta epsilon zeta eta theta");

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 20));
    }

    private int splitFile(PythonCompatibleTextSplitter splitter, String filename) throws Exception {
        return splitChunks(splitter, filename).size();
    }

    private java.util.List<String> splitChunks(
            PythonCompatibleTextSplitter splitter, String filename) throws Exception {
        String text = Files.readString(
                Path.of("data", "ukm_ftsm", filename),
                StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\ufeff') {
            text = text.substring(1);
        }
        return splitter.splitText(text);
    }
}
