package com.ftsm.rag.service;

import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class IndexingService {

    private final VectorStoreService vectorStoreService;
    private final ModelFactory modelFactory;
    private final SemanticCacheService semanticCacheService;
    private final MeterRegistry meterRegistry;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ftsm-java-indexer");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<String, Object> state = new HashMap<>();
    private List<Path> pendingTargetPaths;
    private boolean pendingFullIndex;

    public IndexingService(VectorStoreService vectorStoreService, ModelFactory modelFactory,
                           SemanticCacheService semanticCacheService, MeterRegistry meterRegistry) {
        this.vectorStoreService = vectorStoreService;
        this.modelFactory = modelFactory;
        this.semanticCacheService = semanticCacheService;
        this.meterRegistry = meterRegistry;
        
        state.put("running", false);
        state.put("pending", false);
        state.put("last_result", null);
        state.put("last_error", null);

        Gauge.builder("rag.indexing.running", state, s -> Boolean.TRUE.equals(s.get("running")) ? 1 : 0)
                .description("Is indexing currently running (1 = true, 0 = false)")
                .register(meterRegistry);
        
        Gauge.builder("rag.indexing.pending", state, s -> Boolean.TRUE.equals(s.get("pending")) ? 1 : 0)
                .description("Is indexing pending (1 = true, 0 = false)")
                .register(meterRegistry);
    }

    public synchronized Map<String, Object> getTrainingState() {
        return new HashMap<>(state);
    }

    public synchronized boolean startTraining() {
        return startTraining(null);
    }

    public synchronized boolean startTraining(List<Path> targetPaths) {
        if (Boolean.TRUE.equals(state.get("running"))) {
            state.put("pending", true);
            if (targetPaths == null) {
                pendingFullIndex = true;
                pendingTargetPaths = null;
            } else if (!pendingFullIndex) {
                LinkedHashSet<Path> merged = new LinkedHashSet<>();
                if (pendingTargetPaths != null) {
                    merged.addAll(pendingTargetPaths);
                }
                merged.addAll(targetPaths);
                pendingTargetPaths = new ArrayList<>(merged);
            }
            return false;
        }

        List<Path> taskTargets = targetPaths == null ? null : List.copyOf(targetPaths);
        state.put("running", true);
        state.put("pending", false);
        state.put("last_result", null);
        state.put("last_error", null);

        executorService.submit(() -> trainingWorker(taskTargets));
        return true;
    }

    private void trainingWorker(List<Path> initialTargetPaths) {
        List<Path> targetPaths = initialTargetPaths;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                log.info("Starting background indexing worker for {}",
                        targetPaths == null ? "all documents" : targetPaths);
                Map<String, Object> result = vectorStoreService.loadDocumentsIncremental(targetPaths);
                if (!Boolean.TRUE.equals(result.get("success"))) {
                    throw new IllegalStateException(
                            String.valueOf(result.getOrDefault("error_summary", "Indexing failed"))
                    );
                }

                semanticCacheService.clear();
                modelFactory.resetModels();
                clearSeedRefreshMarkerIfFullIndex(targetPaths);

                synchronized (this) {
                    state.put("last_result", "success");
                    state.put("last_error", null);
                    if (!Boolean.TRUE.equals(state.get("pending"))) {
                        state.put("running", false);
                        return;
                    }
                    targetPaths = pendingFullIndex
                            ? null
                            : pendingTargetPaths == null
                                    ? List.of()
                                    : List.copyOf(pendingTargetPaths);
                    pendingTargetPaths = null;
                    pendingFullIndex = false;
                    state.put("pending", false);
                }
            } catch (Exception error) {
                log.error("Indexing worker crashed", error);
                synchronized (this) {
                    state.put("running", false);
                    state.put("pending", false);
                    state.put("last_result", null);
                    state.put("last_error", error.getMessage());
                    pendingTargetPaths = null;
                    pendingFullIndex = false;
                }
                return;
            }
        }

        synchronized (this) {
            state.put("running", false);
            state.put("pending", false);
            state.put("last_error", "Indexing was interrupted");
            pendingTargetPaths = null;
            pendingFullIndex = false;
        }
    }

    @PreDestroy
    void stop() {
        executorService.shutdownNow();
        try {
            executorService.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private void clearSeedRefreshMarkerIfFullIndex(List<Path> targetPaths) {
        if (targetPaths != null) {
            return;
        }
        String marker = System.getenv("FTSM_SEED_REFRESH_MARKER");
        if (marker == null || marker.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(marker).toAbsolutePath().normalize());
        } catch (Exception error) {
            log.warn("Failed to clear bundled knowledge refresh marker: {}", error.getMessage());
        }
    }
}
