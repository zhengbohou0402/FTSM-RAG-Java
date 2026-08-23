package com.ftsm.rag.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;

import java.util.ArrayList;
import java.util.List;

public class HeaderTextSplitter {

    private final int maxChunkSize;

    public HeaderTextSplitter(int maxChunkSize) {
        this.maxChunkSize = maxChunkSize;
    }

    public List<Document> split(Document document) {
        List<Document> chunks = new ArrayList<>();
        String text = document.text();
        String[] lines = text.split("\n");

        StringBuilder currentChunk = new StringBuilder();
        String currentLevel = "";
        String currentHeading = "";

        for (String line : lines) {
            boolean isNewHeading = false;
            String headingLvl = "";
            String headingTxt = "";

            if (line.startsWith("## ")) {
                isNewHeading = true;
                headingLvl = "2";
                headingTxt = line.substring(3).trim();
            } else if (line.startsWith("### ")) {
                isNewHeading = true;
                headingLvl = "3";
                headingTxt = line.substring(4).trim();
            }

            // If we hit a new H2 or H3, OR if the current chunk exceeds maxChunkSize, we split.
            if ((isNewHeading && currentChunk.length() > 0) || (currentChunk.length() + line.length() > maxChunkSize)) {
                // Save current chunk
                Metadata meta = document.metadata().copy();
                if (!currentLevel.isEmpty()) {
                    meta.put("heading_level", currentLevel);
                    meta.put("heading_text", currentHeading);
                }
                chunks.add(Document.from(currentChunk.toString().trim(), meta));
                
                // Start new chunk
                currentChunk = new StringBuilder();
                if (isNewHeading) {
                    currentLevel = headingLvl;
                    currentHeading = headingTxt;
                }
            } else if (isNewHeading && currentChunk.length() == 0) {
                // Just starting the first chunk which is a heading
                currentLevel = headingLvl;
                currentHeading = headingTxt;
            }

            currentChunk.append(line).append("\n");
        }

        if (currentChunk.length() > 0) {
            Metadata meta = document.metadata().copy();
            if (!currentLevel.isEmpty()) {
                meta.put("heading_level", currentLevel);
                meta.put("heading_text", currentHeading);
            }
            chunks.add(Document.from(currentChunk.toString().trim(), meta));
        }

        return chunks;
    }

    public List<Document> split(List<Document> documents) {
        List<Document> result = new ArrayList<>();
        for (Document doc : documents) {
            result.addAll(split(doc));
        }
        return result;
    }
}
