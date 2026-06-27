package com.ftsm.rag.controller;

import com.ftsm.rag.config.AppConfig;
import com.ftsm.rag.model.IndexState;
import com.ftsm.rag.model.ManifestData;
import com.ftsm.rag.store.DocumentManifestManager;
import com.ftsm.rag.service.CrawlScheduler;
import com.ftsm.rag.service.SemanticCacheService;
import com.ftsm.rag.service.VectorStoreService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class StatsController {

    private final AppConfig appConfig;
    private final DocumentManifestManager manifestManager;
    private final SemanticCacheService semanticCacheService;
    private final CrawlScheduler crawlScheduler;
    private final VectorStoreService vectorStoreService;

    public StatsController(AppConfig appConfig, DocumentManifestManager manifestManager,
                           SemanticCacheService semanticCacheService, CrawlScheduler crawlScheduler,
                           VectorStoreService vectorStoreService) {
        this.appConfig = appConfig;
        this.manifestManager = manifestManager;
        this.semanticCacheService = semanticCacheService;
        this.crawlScheduler = crawlScheduler;
        this.vectorStoreService = vectorStoreService;
    }

    @GetMapping("/cache/stats")
    public Map<String, Object> getCacheStats() {
        return semanticCacheService.stats();
    }

    @GetMapping("/scheduler/status")
    public Map<String, Object> getSchedulerStatus() {
        return crawlScheduler.getStatus();
    }

    @PostMapping("/knowledge/update")
    public Map<String, Object> updateKnowledge(
            @RequestParam(value = "max_pages", required = false) Integer maxPages,
            @RequestParam(value = "reindex", defaultValue = "true") boolean reindex) {
        if (reindex) {
            semanticCacheService.clear();
        }
        return crawlScheduler.triggerManualCrawl(maxPages, reindex);
    }

    @GetMapping("/knowledge/stats")
    public Mono<Map<String, Object>> getKnowledgeStats() {
        return Mono.fromCallable(this::buildKnowledgeStats)
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> buildKnowledgeStats() {
        ManifestData manifest = manifestManager.loadManifest();
        IndexState indexState = manifest.getIndex();
        
        int totalChunks = manifest.getDocuments().values().stream()
                .mapToInt(record -> record.getChunkIds().size())
                .sum();

        String lastIndexed = null;
        for (var record : manifest.getDocuments().values()) {
            String at = record.getIndexedAt();
            if (at != null) {
                if (lastIndexed == null || at.compareTo(lastIndexed) > 0) {
                    lastIndexed = at;
                }
            }
        }

        // Count physical files
        int documentCount = 0;
        Path dataDir = Paths.get(appConfig.getQdrant().getDataPath());
        List<String> allowedTypes = appConfig.getQdrant().getAllowKnowledgeFileTypes();
        if (Files.exists(dataDir)) {
            try (var stream = Files.list(dataDir)) {
                documentCount = (int) stream
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            int dot = name.lastIndexOf('.');
                            return dot > 0 && allowedTypes.contains(name.substring(dot + 1).toLowerCase());
                        })
                        .count();
            } catch (IOException ignored) {}
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("document_count", documentCount);
        stats.put("manifest_records", manifest.getDocuments().size());
        stats.put("total_chunks", indexState != null ? indexState.getTotalChunks() : totalChunks);
        stats.put("last_indexed", indexState != null ? indexState.getLastIndexed() : lastIndexed);
        stats.put("cache_entries", semanticCacheService.stats().get("size"));
        stats.put("index_version", indexState != null ? indexState.getVersion() : 0);
        stats.put("index_updated_at", indexState != null ? indexState.getUpdatedAt() : null);
        stats.put("index_last_error", indexState != null ? indexState.getLastError() : null);
        stats.put("source_type_counts", indexState != null ? indexState.getSourceTypeCounts() : new HashMap<>());
        stats.putAll(vectorStoreService.getIndexHealth());

        return stats;
    }
}
