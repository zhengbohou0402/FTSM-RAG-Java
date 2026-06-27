package com.ftsm.rag.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Central, project-root-relative path resolver.
 *
 * <p>Every relative path configured in {@link AppConfig} ({@code dataPath},
 * {@code lexicalIndexPath}, {@code localStoragePath}, conversation / cache /
 * crawler files, required seed documents) is resolved against a single
 * {@code app.project-root} so that the IDE, a standalone JAR, and the packaged
 * Tauri desktop app all address exactly the same files. The project root is
 * resolved once and reused by every caller instead of each caller doing its
 * own {@code Paths.get(...)} against the JVM working directory.
 */
@Slf4j
@Component
public class AppPaths {

    private final AppConfig appConfig;
    private final Path projectRoot;

    public AppPaths(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.projectRoot = resolveProjectRoot(appConfig.getProjectRoot());
        log.info("AppPaths project root: {}", this.projectRoot);
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    /** Resolves a configured (possibly relative) path against the project root. */
    public Path resolve(String configured) {
        if (configured == null || configured.isBlank()) {
            return projectRoot;
        }
        Path p = Paths.get(configured);
        if (p.isAbsolute()) {
            return p.toAbsolutePath().normalize();
        }
        return projectRoot.resolve(configured).toAbsolutePath().normalize();
    }

    public Path dataDir() {
        return resolve(appConfig.getQdrant().getDataPath());
    }

    public Path manifestPath() {
        return dataDir().resolve("ingestion_manifest.json");
    }

    public Path conversationDir() {
        return dataDir().resolve("conversations");
    }

    public Path conversationIndexFile() {
        return conversationDir().resolve("index.json");
    }

    public Path semanticCacheFile() {
        return dataDir().resolve("semantic_cache.json");
    }

    public Path crawlerLastRunFile() {
        return dataDir().resolve(".last_crawl");
    }

    public Path crawlerOutputFile() {
        return dataDir().resolve(appConfig.getCrawler().getOutputFilename());
    }

    public Path lexicalIndexBase() {
        return resolve(appConfig.getQdrant().getLexicalIndexPath());
    }

    public Path localQdrantExecutable() {
        return resolve(appConfig.getQdrant().getLocalExecutable());
    }

    public Path localQdrantStorage() {
        return resolve(appConfig.getQdrant().getLocalStoragePath());
    }

    public Path seedDocument(String name) {
        return dataDir().resolve(name);
    }

    /**
     * Resolves the stable project root directory.
     * Priority:
     * 1. Explicitly configured projectRoot (if non-empty and valid directory)
     * 2. Auto-detect from class location (JAR -> JAR parent dir; IDE -> navigate to pom.xml)
     * 3. Fallback to user.dir
     */
    public static Path resolveProjectRoot(String configuredRoot) {
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            Path path = Paths.get(configuredRoot).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                return path;
            }
            log.warn("Configured project-root is not a valid directory: {}, falling back to auto-detect",
                    configuredRoot);
        }

        try {
            URL url = AppPaths.class.getProtectionDomain().getCodeSource().getLocation();
            if (url != null) {
                Path classPath = Paths.get(url.toURI()).toAbsolutePath().normalize();
                if (Files.isRegularFile(classPath)) {
                    Path jarDir = classPath.getParent();
                    if (jarDir != null && hasProjectMarker(jarDir)) {
                        return jarDir;
                    }
                } else {
                    Path p = classPath;
                    while (p != null) {
                        if (hasProjectMarker(p)) {
                            return p;
                        }
                        p = p.getParent();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Auto-detect from class location failed: {}", e.getMessage());
        }

        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private static boolean hasProjectMarker(Path path) {
        return Files.isDirectory(path.resolve("data/ukm_ftsm"))
                || Files.exists(path.resolve("pom.xml"))
                || Files.isDirectory(path.resolve("src"));
    }
}
