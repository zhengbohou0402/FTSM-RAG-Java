package com.ftsm.rag.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.ftsm.rag.config.AppConfig;
import com.ftsm.rag.config.AppPaths;
import com.ftsm.rag.model.IndexState;
import com.ftsm.rag.model.ManifestData;
import com.ftsm.rag.model.ManifestRecord;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Component
public class DocumentManifestManager {

    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;
    private final Path manifestPath;
    private final Path projectRoot;

    private static final Set<String> GENERATED_SUMMARY_FILES = Set.of(
            "advisors_expertise_index.txt",
            "student_portal_search_index.txt",
            "student_portal_system_cards.txt",
            "background_and_positioning.txt"
    );

    private static final List<String> COMMUNITY_GUIDE_PATTERNS = List.of(
            "student_portal", "registration_renewal", "campus_bus",
            "chinese_student", "china_student", "international_student"
    );

    private static final List<String> OFFICIAL_PATTERNS = List.of(
            "academic_calendar", "advisors_and_academic_staff", "coursework_timetable",
            "facilities_and_services", "graduation_certification", "industrial_training",
            "malaysian_public_holidays", "master_coursemode", "programmes_and_admissions",
            "semester2_exam_schedule"
    );

    public DocumentManifestManager(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.objectMapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.projectRoot = resolveProjectRoot(appConfig.getProjectRoot());
        this.manifestPath = projectRoot.resolve(appConfig.getQdrant().getDataPath())
                .resolve("ingestion_manifest.json");
        log.info("Project root resolved to: {}", this.projectRoot);
        log.info("Manifest path: {}", this.manifestPath);
    }

    /**
     * Resolves the stable project root directory.
     * Priority:
     * 1. Explicitly configured projectRoot (if non-empty and valid directory)
     * 2. Auto-detect from class location (JAR -> JAR parent dir; IDE -> navigate to pom.xml)
     * 3. Fallback to user.dir
     */
    static Path resolveProjectRoot(String configuredRoot) {
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            Path path = Paths.get(configuredRoot).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                return path;
            }
            log.warn("Configured project-root is not a valid directory: {}, falling back to auto-detect", configuredRoot);
        }

        // Try from class location
        try {
            URL url = DocumentManifestManager.class.getProtectionDomain().getCodeSource().getLocation();
            if (url != null) {
                Path classPath = Paths.get(url.toURI()).toAbsolutePath().normalize();
                if (Files.isRegularFile(classPath)) {
                    // JAR mode: use the directory containing the JAR
                    Path jarDir = classPath.getParent();
                    if (jarDir != null && hasProjectMarker(jarDir)) {
                        return jarDir;
                    }
                } else {
                    // IDE/classes mode: navigate up from target/classes
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

        // Fallback to user.dir
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return cwd;
    }

    private static boolean hasProjectMarker(Path path) {
        return Files.isDirectory(path.resolve("data/ukm_ftsm"))
                || Files.exists(path.resolve("pom.xml"))
                || Files.isDirectory(path.resolve("src"));
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    public synchronized ManifestData loadManifest() {
        if (!Files.exists(manifestPath)) {
            return createEmptyManifest();
        }
        try {
            ManifestData manifest = objectMapper.readValue(manifestPath.toFile(), ManifestData.class);
            if (manifest.getDocuments() == null) {
                manifest.setDocuments(new HashMap<>());
            }
            if (manifest.getIndex() == null) {
                manifest.setIndex(new IndexState());
            }
            return manifest;
        } catch (IOException e) {
            log.error("Failed to parse manifest JSON, returning empty manifest", e);
            return createEmptyManifest();
        }
    }

    private ManifestData createEmptyManifest() {
        ManifestData m = new ManifestData();
        m.setDocuments(new HashMap<>());
        m.setIndex(new IndexState());
        return m;
    }

    public synchronized void saveManifest(ManifestData manifest) {
        try {
            Files.createDirectories(manifestPath.getParent());
            Path tempPath = manifestPath.resolveSibling(manifestPath.getFileName() + ".tmp");
            objectMapper.writeValue(tempPath.toFile(), manifest);
            try {
                Files.move(tempPath, manifestPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveError) {
                Files.move(tempPath, manifestPath, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Saved manifest to {}", manifestPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save manifest file", e);
        }
    }

    public SourceClassification classifySource(Path path) {
        String filename = path.getFileName().toString().toLowerCase();
        String stem = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;
        String sourceType;

        if ("ftsm_official_website.txt".equals(filename)) {
            sourceType = "scraped_website";
        } else if (GENERATED_SUMMARY_FILES.contains(filename) || stem.endsWith("_index")) {
            sourceType = "generated_summary";
        } else if (COMMUNITY_GUIDE_PATTERNS.stream().anyMatch(stem::contains)) {
            sourceType = "community_guide";
        } else if (OFFICIAL_PATTERNS.stream().anyMatch(stem::contains)) {
            sourceType = "official";
        } else {
            sourceType = "community_guide"; // default fallback
        }

        return getClassificationForType(sourceType);
    }

    public SourceClassification getClassificationForType(String sourceType) {
        switch (sourceType) {
            case "official":
                return new SourceClassification("official", "Official material",
                        "Curated from official UKM/FTSM material or structured academic documents.", 1);
            case "scraped_website":
                return new SourceClassification("scraped_website", "Scraped official website",
                        "Captured from the public FTSM website by the scheduled crawler.", 2);
            case "generated_summary":
                return new SourceClassification("generated_summary", "Generated summary",
                        "Generated or consolidated index/summary derived from other project material.", 4);
            case "community_guide":
            default:
                return new SourceClassification("community_guide", "Student guide",
                        "Practical guide or student-facing notes; useful but should be verified for formal decisions.", 3);
        }
    }

    /**
     * Builds a stable document ID that is byte-for-byte compatible with Python
     * {@code stable_file_doc_id()} in {@code rag/ingestion.py}.
     *
     * <p>The returned ID has the form {@code file:data/ukm_ftsm/filename.txt}
     * where the path is relative to the <em>project root</em> (not the data
     * directory). This keeps IDs stable across IDE, JAR, and Tauri launches.
     */
    public String stableFileDocId(Path path) {
        Path resolved = path.toAbsolutePath().normalize();
        Path root = projectRoot.toAbsolutePath().normalize();
        String relPath;
        try {
            relPath = root.relativize(resolved).toString().replace('\\', '/');
            if (relPath.startsWith("..") || Paths.get(relPath).isAbsolute()) {
                // The file lives outside the project root – fall back to
                // the absolute path so callers still receive a stable id.
                relPath = resolved.toString().replace('\\', '/');
            }
        } catch (IllegalArgumentException e) {
            relPath = resolved.toString().replace('\\', '/');
        }
        return "file:" + relPath;
    }

    public String getUtcIsoNow() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    public void updateManifestIndexState(ManifestData manifest, String lastError, boolean bumpVersion, String pipelineFingerprint) {
        IndexState state = manifest.getIndex();
        if (state == null) {
            state = new IndexState();
            manifest.setIndex(state);
        }

        int totalChunks = 0;
        Map<String, Integer> sourceTypeCounts = new HashMap<>();
        String lastIndexed = null;

        for (ManifestRecord record : manifest.getDocuments().values()) {
            if (record.getChunkIds() == null) {
                record.setChunkIds(new ArrayList<>());
            }
            if (record.getExtra() == null) {
                record.setExtra(new HashMap<>());
            }
            totalChunks += record.getChunkIds().size();
            String st = record.getSourceType();

            // Retroactively fix empty source type
            if (st == null || st.isEmpty() || "local_file".equals(st) || "unknown".equals(st)) {
                String filename = (String) record.getExtra().getOrDefault("filename", "");
                SourceClassification classification = classifySource(Paths.get(filename));
                st = classification.getType();
                record.setSourceType(st);
                record.getExtra().put("source_trust_label", classification.getLabel());
                record.getExtra().put("source_trust_note", classification.getNote());
                record.getExtra().put("source_priority", classification.getPriority());
            }

            sourceTypeCounts.put(st, sourceTypeCounts.getOrDefault(st, 0) + 1);

            String indexedAt = record.getIndexedAt();
            if (indexedAt != null) {
                if (lastIndexed == null || indexedAt.compareTo(lastIndexed) > 0) {
                    lastIndexed = indexedAt;
                }
            }
        }

        if (bumpVersion) {
            state.setVersion(state.getVersion() + 1);
        }

        state.setUpdatedAt(getUtcIsoNow());
        state.setLastIndexed(lastIndexed);
        state.setDocumentCount(manifest.getDocuments().size());
        state.setTotalChunks(totalChunks);
        state.setSourceTypeCounts(sourceTypeCounts);
        state.setLastError(lastError);
        if (pipelineFingerprint != null) {
            state.setPipelineFingerprint(pipelineFingerprint);
        }
    }

    @Data
    public static class SourceClassification {
        private final String type;
        private final String label;
        private final String note;
        private final int priority;
    }
}
