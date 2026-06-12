package com.ftsm.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ftsm.rag.config.AppConfig;
import com.ftsm.rag.model.CacheData;
import com.ftsm.rag.model.CacheEntry;
import dev.langchain4j.data.embedding.Embedding;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SemanticCacheService {

    private final AppConfig appConfig;
    private final ModelFactory modelFactory;
    private final ObjectMapper objectMapper;
    private final Path cacheFile;
    private final ReentrantLock lock = new ReentrantLock();

    private final List<CacheEntry> entries = new CopyOnWriteArrayList<>();
    private int hitCount = 0;
    private int missCount = 0;

    public SemanticCacheService(AppConfig appConfig, ModelFactory modelFactory) {
        this.appConfig = appConfig;
        this.modelFactory = modelFactory;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        
        this.cacheFile = Paths.get(appConfig.getQdrant().getDataPath(), "semantic_cache.json");
        loadCache();
    }

    public static double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a.size() != b.size() || a.isEmpty()) return 0.0;
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            double valA = a.get(i);
            double valB = b.get(i);
            dotProduct += valA * valB;
            normA += valA * valA;
            normB += valB * valB;
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private List<Double> embed(String text) {
        Embedding embedding = modelFactory.getEmbeddingModel().embed(text).content();
        List<Double> list = new ArrayList<>();
        for (float f : embedding.vector()) {
            list.add((double) f);
        }
        return list;
    }

    public CacheResult get(String question, String namespace) {
        List<Double> qVec;
        try {
            qVec = embed(question);
        } catch (Exception e) {
            log.error("[SemanticCache] Embedding failed during lookup: {}", e.getMessage());
            return new CacheResult(false, null);
        }

        long now = Instant.now().getEpochSecond();
        long ttlSeconds = (long) appConfig.getCache().getTtlDays() * 24 * 3600;

        double bestScore = 0.0;
        String bestAnswer = null;

        lock.lock();
        try {
            for (CacheEntry entry : entries) {
                if (!Objects.equals(
                        Optional.ofNullable(entry.getNamespace()).orElse(""),
                        Optional.ofNullable(namespace).orElse("")
                )) {
                    continue;
                }
                // Skip expired entries
                if (now - entry.getCreatedAt() > ttlSeconds) {
                    continue;
                }
                double score = cosineSimilarity(qVec, entry.getVector());
                if (score > bestScore) {
                    bestScore = score;
                    bestAnswer = entry.getAnswer();
                }
            }
        } finally {
            lock.unlock();
        }

        double threshold = appConfig.getCache().getThreshold();
        if (bestScore >= threshold && bestAnswer != null) {
            log.info("[SemanticCache] HIT similarity={}}, q={}", String.format("%.4f", bestScore), 
                    question.substring(0, Math.min(question.length(), 60)));
            lock.lock();
            try {
                hitCount++;
            } finally {
                lock.unlock();
            }
            return new CacheResult(true, bestAnswer);
        }

        log.info("[SemanticCache] MISS similarity={}, q={}", String.format("%.4f", bestScore), 
                question.substring(0, Math.min(question.length(), 60)));
        lock.lock();
        try {
            missCount++;
        } finally {
            lock.unlock();
        }
        return new CacheResult(false, null);
    }

    public void set(String question, String answer, String namespace) {
        List<Double> qVec;
        try {
            qVec = embed(question);
        } catch (Exception e) {
            log.error("[SemanticCache] Embedding failed during insertion: {}", e.getMessage());
            return;
        }

        CacheEntry entry = new CacheEntry();
        entry.setNamespace(namespace);
        entry.setQuestion(question);
        entry.setAnswer(answer);
        entry.setVector(qVec);
        entry.setCreatedAt(Instant.now().getEpochSecond());

        lock.lock();
        try {
            entries.add(entry);
            int maxEntries = appConfig.getCache().getMaxEntries();
            if (entries.size() > maxEntries) {
                // Remove oldest entries
                int toRemove = entries.size() - maxEntries;
                for (int i = 0; i < toRemove; i++) {
                    entries.remove(0);
                }
            }
            saveCache();
        } finally {
            lock.unlock();
        }

        log.info("[SemanticCache] SET q={}", question.substring(0, Math.min(question.length(), 60)));
    }

    public Map<String, Object> stats() {
        lock.lock();
        try {
            int total = entries.size();
            long now = Instant.now().getEpochSecond();
            long ttlSeconds = (long) appConfig.getCache().getTtlDays() * 24 * 3600;
            
            long validCount = entries.stream()
                    .filter(e -> (now - e.getCreatedAt()) <= ttlSeconds)
                    .count();

            Map<String, Integer> namespaceDistribution = new HashMap<>();
            for (CacheEntry entry : entries) {
                String namespace = entry.getNamespace();
                String ns = namespace == null || namespace.isEmpty() ? "legacy" : namespace;
                namespaceDistribution.put(ns, namespaceDistribution.getOrDefault(ns, 0) + 1);
            }

            int totalQueries = hitCount + missCount;
            double hitRate = totalQueries > 0 ? (double) hitCount / totalQueries : 0.0;
            // Round to 4 decimal places
            hitRate = Math.round(hitRate * 10000.0) / 10000.0;

            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("size", total);
            stats.put("valid", validCount);
            stats.put("threshold", appConfig.getCache().getThreshold());
            stats.put("hit_count", hitCount);
            stats.put("miss_count", missCount);
            stats.put("hit_rate", hitRate);
            stats.put("namespaces", namespaceDistribution);
            return stats;
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            entries.clear();
            hitCount = 0;
            missCount = 0;
            saveCache();
            log.info("[SemanticCache] CLEARED");
        } finally {
            lock.unlock();
        }
    }

    // --- File Storage ---

    private void loadCache() {
        if (!Files.exists(cacheFile)) {
            return;
        }
        lock.lock();
        try {
            CacheData data = objectMapper.readValue(cacheFile.toFile(), CacheData.class);
            if (data != null && data.getEntries() != null) {
                entries.addAll(data.getEntries());
                log.info("[SemanticCache] Loaded {} entries from disk", entries.size());
            }
        } catch (IOException e) {
            log.warn("[SemanticCache] Failed to load cache file: {}", e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    private void saveCache() {
        try {
            Files.createDirectories(cacheFile.getParent());
            CacheData data = new CacheData();
            data.setEntries(new ArrayList<>(entries));
            objectMapper.writeValue(cacheFile.toFile(), data);
        } catch (IOException e) {
            log.error("[SemanticCache] Failed to save cache file", e);
        }
    }

    @Data
    public static class CacheResult {
        private final boolean hit;
        private final String answer;
    }
}
