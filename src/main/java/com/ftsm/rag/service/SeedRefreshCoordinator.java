package com.ftsm.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Component
public class SeedRefreshCoordinator implements ApplicationRunner {

    private final SettingsService settingsService;
    private final IndexingService indexingService;
    private final Path refreshMarker;

    public SeedRefreshCoordinator(SettingsService settingsService, IndexingService indexingService) {
        this.settingsService = settingsService;
        this.indexingService = indexingService;
        String marker = System.getenv("FTSM_SEED_REFRESH_MARKER");
        this.refreshMarker = marker == null || marker.isBlank()
                ? null
                : Paths.get(marker).toAbsolutePath().normalize();
        settingsService.registerListener(this::startRefreshIfReady);
    }

    @Override
    public void run(ApplicationArguments args) {
        startRefreshIfReady();
    }

    private void startRefreshIfReady() {
        if (refreshMarker == null
                || !Files.isRegularFile(refreshMarker)
                || !settingsService.isDashScopeConfigured()) {
            return;
        }
        boolean started = indexingService.startTraining();
        log.info("Bundled knowledge refresh {}", started ? "started" : "queued");
    }
}
