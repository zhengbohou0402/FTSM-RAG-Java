package com.ftsm.rag.service;

import com.ftsm.rag.config.AppConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class CrawlScheduler {

    private final AppConfig appConfig;
    private final IndexingService indexingService;
    private final SemanticCacheService semanticCacheService;
    private final FtsmWebsiteCrawler crawler;
    private final MeterRegistry meterRegistry;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ftsm-java-crawler");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Object> state = new LinkedHashMap<>();
    private final Path lastRunFile;
    private volatile long lastSuccessfulEpochSeconds;

    public CrawlScheduler(
            AppConfig appConfig,
            IndexingService indexingService,
            SemanticCacheService semanticCacheService,
            FtsmWebsiteCrawler crawler,
            MeterRegistry meterRegistry) {
        this.appConfig = appConfig;
        this.indexingService = indexingService;
        this.semanticCacheService = semanticCacheService;
        this.crawler = crawler;
        this.meterRegistry = meterRegistry;
        this.lastRunFile = Paths.get(
                appConfig.getQdrant().getDataPath(), ".last_crawl"
        ).toAbsolutePath().normalize();
        this.lastSuccessfulEpochSeconds = readLastRun();

        state.put("running", false);
        state.put("mode", null);
        state.put("phase", "idle");
        state.put("last_success", isoFromEpoch(lastSuccessfulEpochSeconds));
        state.put("last_attempt", null);
        state.put("last_error", null);
        state.put("last_output_file", null);
        state.put("pages_crawled", 0);
        state.put("pages_visited", 0);
        state.put("pages_skipped", 0);
        state.put("pages_failed", 0);
        state.put("crawler_engine", null);
        state.put("current_url", null);

        // Register Metrics
        Gauge.builder("rag.crawler.pages.crawled", state, s -> (Integer) s.getOrDefault("pages_crawled", 0))
                .description("Number of pages successfully crawled")
                .register(meterRegistry);
        Gauge.builder("rag.crawler.pages.visited", state, s -> (Integer) s.getOrDefault("pages_visited", 0))
                .description("Number of pages visited by the crawler")
                .register(meterRegistry);
        Gauge.builder("rag.crawler.pages.skipped", state, s -> (Integer) s.getOrDefault("pages_skipped", 0))
                .description("Number of pages skipped by the crawler")
                .register(meterRegistry);
        Gauge.builder("rag.crawler.pages.failed", state, s -> (Integer) s.getOrDefault("pages_failed", 0))
                .description("Number of pages failed during crawling")
                .register(meterRegistry);
    }

    @PostConstruct
    void startScheduledCrawler() {
        if (!appConfig.getCrawler().isEnabled()) {
            log.info("Website crawler scheduler is disabled");
            return;
        }
        long intervalSeconds = intervalSeconds();
        long initialDelay = secondsUntilNextRun();
        executor.scheduleWithFixedDelay(
                () -> {
                    if (beginRun("scheduled")) {
                        runCrawl(appConfig.getCrawler().getMaxPages(), true, "scheduled");
                    }
                },
                initialDelay,
                intervalSeconds,
                TimeUnit.SECONDS
        );
        log.info("Website crawler scheduled; initial delay={}s interval={}s",
                initialDelay, intervalSeconds);
    }

    public synchronized Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>(state);
        status.put("enabled", appConfig.getCrawler().isEnabled());
        status.put("manual_available", true);
        status.put("interval_hours", appConfig.getCrawler().getIntervalHours());
        status.put("max_pages", appConfig.getCrawler().getMaxPages());
        status.put("last_run", isoFromEpoch(lastSuccessfulEpochSeconds));
        status.put("next_run", appConfig.getCrawler().isEnabled()
                ? Instant.ofEpochSecond(nextRunEpochSeconds()).toString()
                : null);
        status.put("thread_alive", !executor.isShutdown() && !executor.isTerminated());
        status.put("browser_enabled", appConfig.getCrawler().isBrowserEnabled());
        status.put("browser_auto_download", appConfig.getCrawler().isBrowserAutoDownload());
        status.put("static_fallback", appConfig.getCrawler().isStaticFallback());
        status.put("static_tls_fallback", appConfig.getCrawler().isStaticTlsFallback());
        return status;
    }

    public synchronized Map<String, Object> triggerManualCrawl(Integer maxPages, boolean reindex) {
        int pageLimit = maxPages != null
                ? Math.max(1, Math.min(maxPages, 300))
                : appConfig.getCrawler().getMaxPages();
        if (!beginRun("manual")) {
            return Map.of(
                    "started", false,
                    "message", "Knowledge update is already running.",
                    "max_pages", pageLimit,
                    "reindex", reindex
            );
        }
        executor.submit(() -> runCrawl(pageLimit, reindex, "manual"));
        return Map.of(
                "started", true,
                "message", reindex
                        ? "Website crawl and incremental index update started."
                        : "Website crawl started.",
                "max_pages", pageLimit,
                "reindex", reindex
        );
    }

    private synchronized boolean beginRun(String mode) {
        if (Boolean.TRUE.equals(state.get("running"))) {
            return false;
        }
        state.put("running", true);
        state.put("mode", mode);
        state.put("phase", "crawling");
        state.put("last_attempt", Instant.now().toString());
        state.put("last_error", null);
        state.put("pages_crawled", 0);
        state.put("pages_visited", 0);
        state.put("pages_skipped", 0);
        state.put("pages_failed", 0);
        state.put("crawler_engine", null);
        state.put("current_url", null);
        return true;
    }

    private void runCrawl(int maxPages, boolean reindex, String mode) {
        try {
            FtsmWebsiteCrawler.CrawlResult result = crawler.crawl(maxPages, this::updateProgress);
            synchronized (this) {
                state.put("last_output_file", result.outputFile().toString());
                state.put("pages_crawled", result.pagesCrawled());
                state.put("pages_visited", result.pagesVisited());
                state.put("pages_skipped", result.pagesSkipped());
                state.put("pages_failed", result.pagesFailed());
                state.put("crawler_engine", result.engine());
                state.put("current_url", null);
            }

            if (reindex) {
                synchronized (this) {
                    state.put("phase", "indexing");
                }
                semanticCacheService.clear();
                indexingService.startTraining(List.of(result.outputFile()));
                waitForIndexing();
            }

            long successEpoch = writeLastRun();
            log.info("{} website crawl completed: pages={} visited={} skipped={} failed={} engine={}",
                    mode, result.pagesCrawled(), result.pagesVisited(), result.pagesSkipped(),
                    result.pagesFailed(), result.engine());
            synchronized (this) {
                lastSuccessfulEpochSeconds = successEpoch;
                state.put("running", false);
                state.put("mode", null);
                state.put("phase", "idle");
                state.put("last_success", isoFromEpoch(successEpoch));
                state.put("last_error", null);
            }
        } catch (Exception error) {
            log.error("{} website crawl failed", mode, error);
            synchronized (this) {
                state.put("running", false);
                state.put("mode", null);
                state.put("phase", "idle");
                state.put("current_url", null);
                state.put("last_error", error.getMessage());
            }
        }
    }

    private synchronized void updateProgress(FtsmWebsiteCrawler.CrawlProgress progress) {
        state.put("crawler_engine", progress.engine());
        state.put("pages_visited", progress.pagesVisited());
        state.put("pages_crawled", progress.pagesCrawled());
        state.put("pages_skipped", progress.pagesSkipped());
        state.put("pages_failed", progress.pagesFailed());
        state.put("current_url", progress.currentUrl());
    }

    private void waitForIndexing() throws InterruptedException {
        for (int attempt = 0; attempt < 3600; attempt++) {
            Map<String, Object> training = indexingService.getTrainingState();
            if (!Boolean.TRUE.equals(training.get("running"))
                    && !Boolean.TRUE.equals(training.get("pending"))) {
                Object error = training.get("last_error");
                if (error != null) {
                    throw new IllegalStateException(String.valueOf(error));
                }
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Timed out waiting for crawled content indexing");
    }

    private long readLastRun() {
        try {
            return (long) Double.parseDouble(
                    Files.readString(lastRunFile, StandardCharsets.UTF_8).trim()
            );
        } catch (Exception ignored) {
            return 0;
        }
    }

    private long writeLastRun() throws Exception {
        long value = Instant.now().getEpochSecond();
        Files.createDirectories(lastRunFile.getParent());
        Path temp = lastRunFile.resolveSibling(lastRunFile.getFileName() + ".tmp");
        Files.writeString(temp, Long.toString(value), StandardCharsets.UTF_8);
        try {
            Files.move(temp, lastRunFile,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicMoveError) {
            Files.move(temp, lastRunFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return value;
    }

    private long intervalSeconds() {
        return Math.max(1, appConfig.getCrawler().getIntervalHours()) * 3600L;
    }

    private long secondsUntilNextRun() {
        if (lastSuccessfulEpochSeconds == 0) {
            return 0;
        }
        return Math.max(0, nextRunEpochSeconds() - Instant.now().getEpochSecond());
    }

    private long nextRunEpochSeconds() {
        return lastSuccessfulEpochSeconds == 0
                ? Instant.now().getEpochSecond()
                : lastSuccessfulEpochSeconds + intervalSeconds();
    }

    private String isoFromEpoch(long epochSeconds) {
        return epochSeconds <= 0 ? null : Instant.ofEpochSecond(epochSeconds).toString();
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
