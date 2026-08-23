package com.ftsm.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ftsm.rag.config.AppConfig;
import com.ftsm.rag.model.CacheEntry;
import dev.langchain4j.data.embedding.Embedding;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class SemanticCacheService {

    private final AppConfig appConfig;
    private final ModelFactory modelFactory;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    private static final String CACHE_KEY = "semantic_cache:entries";
    
    // Track stats in-memory per node (or could be in Redis, but simple atomic counters are fine for simple stats)
    private final AtomicInteger hitCount = new AtomicInteger(0);
    private final AtomicInteger missCount = new AtomicInteger(0);

    public SemanticCacheService(AppConfig appConfig, ModelFactory modelFactory, StringRedisTemplate redisTemplate) {
        this.appConfig = appConfig;
        this.modelFactory = modelFactory;
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
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

    private List<CacheEntry> loadEntries() {
        List<Object> values = redisTemplate.opsForHash().values(CACHE_KEY);
        List<CacheEntry> entries = new ArrayList<>();
        for (Object val : values) {
            try {
                entries.add(objectMapper.readValue((String) val, CacheEntry.class));
            } catch (IOException e) {
                log.warn("Failed to parse semantic cache entry", e);
            }
        }
        return entries;
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

        List<CacheEntry> entries = loadEntries();

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

        double threshold = appConfig.getCache().getThreshold();
        if (bestScore >= threshold && bestAnswer != null) {
            log.info("[SemanticCache] HIT similarity={}, q={}", String.format("%.4f", bestScore), 
                    question.substring(0, Math.min(question.length(), 60)));
            hitCount.incrementAndGet();
            return new CacheResult(true, bestAnswer);
        }

        log.info("[SemanticCache] MISS similarity={}, q={}", String.format("%.4f", bestScore), 
                question.substring(0, Math.min(question.length(), 60)));
        missCount.incrementAndGet();
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

        String id = UUID.randomUUID().toString();

        try {
            String json = objectMapper.writeValueAsString(entry);
            redisTemplate.opsForHash().put(CACHE_KEY, id, json);
            
            // Prune if exceeds max entries
            Long size = redisTemplate.opsForHash().size(CACHE_KEY);
            int maxEntries = appConfig.getCache().getMaxEntries();
            
            if (size != null && size > maxEntries) {
                Map<Object, Object> allEntries = redisTemplate.opsForHash().entries(CACHE_KEY);
                List<Map.Entry<Object, Object>> sorted = new ArrayList<>(allEntries.entrySet());
                
                // Sort by createdAt ascending (oldest first)
                sorted.sort((a, b) -> {
                    try {
                        CacheEntry ea = objectMapper.readValue((String) a.getValue(), CacheEntry.class);
                        CacheEntry eb = objectMapper.readValue((String) b.getValue(), CacheEntry.class);
                        return Long.compare(ea.getCreatedAt(), eb.getCreatedAt());
                    } catch (IOException ex) {
                        return 0;
                    }
                });
                
                int toRemove = sorted.size() - maxEntries;
                Object[] keysToDelete = sorted.subList(0, toRemove).stream().map(Map.Entry::getKey).toArray();
                if (keysToDelete.length > 0) {
                    redisTemplate.opsForHash().delete(CACHE_KEY, keysToDelete);
                }
            }
        } catch (IOException e) {
            log.error("[SemanticCache] Failed to save entry", e);
        }

        log.info("[SemanticCache] SET q={}", question.substring(0, Math.min(question.length(), 60)));
    }

    public Map<String, Object> stats() {
        List<CacheEntry> entries = loadEntries();
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

        int h = hitCount.get();
        int m = missCount.get();
        int totalQueries = h + m;
        double hitRate = totalQueries > 0 ? (double) h / totalQueries : 0.0;
        // Round to 4 decimal places
        hitRate = Math.round(hitRate * 10000.0) / 10000.0;

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("size", total);
        stats.put("valid", validCount);
        stats.put("threshold", appConfig.getCache().getThreshold());
        stats.put("hit_count", h);
        stats.put("miss_count", m);
        stats.put("hit_rate", hitRate);
        stats.put("namespaces", namespaceDistribution);
        return stats;
    }

    public void clear() {
        redisTemplate.delete(CACHE_KEY);
        hitCount.set(0);
        missCount.set(0);
        log.info("[SemanticCache] CLEARED");
    }

    @Data
    public static class CacheResult {
        private final boolean hit;
        private final String answer;
    }
}
