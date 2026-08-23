package com.ftsm.rag.service;

import dev.langchain4j.data.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Matches LangChain RecursiveCharacterTextSplitter with keep_separator=true.
 */
final class PythonCompatibleTextSplitter {

    static final List<String> DEFAULT_SEPARATORS = List.of(
            "\n\n", "\n", ". ", "! ", "? ", "; ", ": ", ", ", " ", ""
    );

    private final int chunkSize;
    private final int chunkOverlap;
    private final List<String> separators;

    PythonCompatibleTextSplitter(int chunkSize, int chunkOverlap) {
        this(chunkSize, chunkOverlap, DEFAULT_SEPARATORS);
    }

    PythonCompatibleTextSplitter(int chunkSize, int chunkOverlap, List<String> separators) {
        if (chunkSize <= 0 || chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("Invalid chunk size or overlap");
        }
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.separators = List.copyOf(separators);
    }

    List<Document> split(Document document) {
        List<Document> chunks = new ArrayList<>();
        if (document == null || document.text() == null || document.text().trim().isEmpty()) {
            return chunks;
        }
        for (String text : splitText(document.text())) {
            chunks.add(Document.from(text, document.metadata()));
        }
        return chunks;
    }

    List<String> splitText(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        return splitText(normalized, separators);
    }

    private List<String> splitText(String text, List<String> candidates) {
        List<String> finalChunks = new ArrayList<>();
        String separator = candidates.get(candidates.size() - 1);
        List<String> remaining = List.of();

        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.get(i);
            if (candidate.isEmpty()) {
                separator = candidate;
                break;
            }
            if (text.contains(candidate)) {
                separator = candidate;
                remaining = candidates.subList(i + 1, candidates.size());
                break;
            }
        }

        List<String> splits = splitKeepingSeparatorAtStart(text, separator);
        List<String> goodSplits = new ArrayList<>();
        for (String split : splits) {
            if (length(split) < chunkSize) {
                goodSplits.add(split);
                continue;
            }
            if (!goodSplits.isEmpty()) {
                finalChunks.addAll(mergeSplits(goodSplits));
                goodSplits.clear();
            }
            if (remaining.isEmpty()) {
                finalChunks.add(split);
            } else {
                finalChunks.addAll(splitText(split, remaining));
            }
        }
        if (!goodSplits.isEmpty()) {
            finalChunks.addAll(mergeSplits(goodSplits));
        }
        return finalChunks;
    }

    private List<String> splitKeepingSeparatorAtStart(String text, String separator) {
        if (separator.isEmpty()) {
            List<String> characters = new ArrayList<>(text.length());
            text.codePoints().forEach(codePoint ->
                    characters.add(new String(Character.toChars(codePoint))));
            return characters;
        }

        String[] raw = text.split("(?=" + Pattern.quote(separator) + ")", -1);
        List<String> result = new ArrayList<>(raw.length);
        for (String value : raw) {
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    private List<String> mergeSplits(List<String> splits) {
        List<String> documents = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int total = 0;

        for (String split : splits) {
            int splitLength = length(split);
            if (total + splitLength > chunkSize && !current.isEmpty()) {
                addJoined(documents, current);
                while (total > chunkOverlap
                        || (total + splitLength > chunkSize && total > 0)) {
                    total -= length(current.remove(0));
                }
            }
            current.add(split);
            total += splitLength;
        }
        addJoined(documents, current);
        return documents;
    }

    private void addJoined(List<String> documents, List<String> parts) {
        String joined = String.join("", parts).trim();
        if (!joined.isEmpty()) {
            documents.add(joined);
        }
    }

    private int length(String value) {
        return value.codePointCount(0, value.length());
    }
}
