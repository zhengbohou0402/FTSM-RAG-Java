package com.ftsm.rag.service;

import com.ftsm.rag.config.AppConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrawlSchedulerTest {

    @TempDir
    Path tempDir;

    @Test
    void manualCrawlPublishesProgressAndIndexesOnlyCrawlerOutput() throws Exception {
        AppConfig config = new AppConfig();
        config.getQdrant().setDataPath(tempDir.toString());
        config.getCrawler().setEnabled(false);

        IndexingService indexing = mock(IndexingService.class);
        when(indexing.startTraining(any())).thenReturn(true);
        Map<String, Object> trainingState = new HashMap<>();
        trainingState.put("running", false);
        trainingState.put("pending", false);
        trainingState.put("last_error", null);
        when(indexing.getTrainingState()).thenReturn(trainingState);
        SemanticCacheService cache = mock(SemanticCacheService.class);
        FtsmWebsiteCrawler crawler = mock(FtsmWebsiteCrawler.class);
        Path output = tempDir.resolve("ftsm_official_website.txt").toAbsolutePath();
        when(crawler.crawl(eq(3), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            var progress = (java.util.function.Consumer<FtsmWebsiteCrawler.CrawlProgress>)
                    invocation.getArgument(1);
            progress.accept(new FtsmWebsiteCrawler.CrawlProgress(
                    "msedge", 3, 2, 0, 1, "https://www.ftsm.ukm.my/v6"
            ));
            return new FtsmWebsiteCrawler.CrawlResult(output, 2, 3, 0, 1, "msedge");
        });

        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        CrawlScheduler scheduler = new CrawlScheduler(config, indexing, cache, crawler, meterRegistry);
        Map<String, Object> started = scheduler.triggerManualCrawl(3, true);
        assertTrue((Boolean) started.get("started"));

        Map<String, Object> status = waitUntilFinished(scheduler);
        assertFalse((Boolean) status.get("running"));
        assertEquals(2, status.get("pages_crawled"));
        assertEquals(3, status.get("pages_visited"));
        assertEquals(1, status.get("pages_failed"));
        assertEquals("msedge", status.get("crawler_engine"));
        assertEquals(output.toString(), status.get("last_output_file"));
        assertTrue(status.get("last_success") != null);
        verify(indexing).startTraining(java.util.List.of(output));
        verify(cache).clear();
        scheduler.stop();
    }

    private Map<String, Object> waitUntilFinished(CrawlScheduler scheduler) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            Map<String, Object> status = scheduler.getStatus();
            if (!Boolean.TRUE.equals(status.get("running"))) {
                return status;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Crawler did not finish");
    }
}
