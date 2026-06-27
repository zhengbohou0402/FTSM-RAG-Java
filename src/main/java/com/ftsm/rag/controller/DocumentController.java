package com.ftsm.rag.controller;

import com.ftsm.rag.config.AppConfig;
import com.ftsm.rag.model.ManifestData;
import com.ftsm.rag.model.ManifestRecord;
import com.ftsm.rag.store.DocumentManifestManager;
import com.ftsm.rag.utils.FileExtractors;
import com.ftsm.rag.service.IndexingService;
import com.ftsm.rag.service.SemanticCacheService;
import com.ftsm.rag.service.VectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api")
public class DocumentController {

    private final AppConfig appConfig;
    private final DocumentManifestManager manifestManager;
    private final VectorStoreService vectorStoreService;
    private final IndexingService indexingService;
    private final SemanticCacheService semanticCacheService;

    public DocumentController(AppConfig appConfig, DocumentManifestManager manifestManager,
                              VectorStoreService vectorStoreService, IndexingService indexingService,
                              SemanticCacheService semanticCacheService) {
        this.appConfig = appConfig;
        this.manifestManager = manifestManager;
        this.vectorStoreService = vectorStoreService;
        this.indexingService = indexingService;
        this.semanticCacheService = semanticCacheService;
    }

    private Path getSafeDocumentPath(String filename, Path dataDir) {
        String cleanName = filename.replace("\\", "/").replaceAll("^/+", "");
        Path rel = Paths.get(cleanName);
        if (rel.isAbsolute() || rel.toString().contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid document path");
        }
        Path resolved = dataDir.resolve(rel).toAbsolutePath().normalize();
        Path root = dataDir.toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid document path");
        }
        return resolved;
    }

    @GetMapping("/documents")
    public Mono<Map<String, List<Map<String, Object>>>> listDocuments() {
        return Mono.fromCallable(() -> {
            Path dataDir = Paths.get(appConfig.getQdrant().getDataPath());
            if (!Files.exists(dataDir)) {
                Files.createDirectories(dataDir);
            }

            ManifestData manifest = manifestManager.loadManifest();
            Map<String, ManifestRecord> indexedDocs = manifest.getDocuments();
            List<String> allowedTypes = appConfig.getQdrant().getAllowKnowledgeFileTypes();

            List<Map<String, Object>> docList = new ArrayList<>();
            
            try (var stream = Files.list(dataDir)) {
                List<Path> files = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString();
                            int dot = name.lastIndexOf('.');
                            if (dot > 0) {
                                String ext = name.substring(dot + 1).toLowerCase();
                                return allowedTypes.contains(ext);
                            }
                            return false;
                        })
                        .collect(Collectors.toList());

                for (Path path : files) {
                    String relName = dataDir.relativize(path).toString().replace('\\', '/');
                    String ext = relName.substring(relName.lastIndexOf('.') + 1).toLowerCase();
                    long size = Files.size(path);
                    long modified = Files.getLastModifiedTime(path).toMillis() / 1000;
                    
                    String docId = manifestManager.stableFileDocId(path);
                    ManifestRecord record = indexedDocs.get(docId);
                    
                    boolean indexed = record != null;
                    String currentHash = FileExtractors.getFileSha256Hex(path);
                    boolean stale = record != null && !currentHash.equals(record.getHash());

                    String coveredBy = null;
                    if (!indexed && "pdf".equals(ext)) {
                        // Check if a .txt file with same base name exists and is indexed
                        String nameWithoutExt = relName.substring(0, relName.lastIndexOf('.'));
                        Path transcriptPath = dataDir.resolve(nameWithoutExt + ".txt");
                        if (Files.exists(transcriptPath)) {
                            String transId = manifestManager.stableFileDocId(transcriptPath);
                            if (indexedDocs.containsKey(transId)) {
                                coveredBy = nameWithoutExt + ".txt";
                            }
                        }
                    }

                    Map<String, Object> docMap = new LinkedHashMap<>();
                    docMap.put("name", relName);
                    docMap.put("size", size);
                    docMap.put("modified", modified);
                    docMap.put("indexed", indexed && !stale);
                    docMap.put("stale", stale);
                    docMap.put("chunks", record != null ? record.getChunkIds().size() : 0);
                    docMap.put("source_type", record != null ? record.getSourceType() : null);
                    docMap.put("source_trust_label", record != null && record.getExtra() != null 
                            ? record.getExtra().get("source_trust_label") : null);
                    docMap.put("indexed_at", record != null ? record.getIndexedAt() : null);
                    docMap.put("covered_by", coveredBy);
                    docMap.put("index_note", coveredBy != null ? "Covered by indexed transcript " + coveredBy : null);

                    docList.add(docMap);
                }
            }

            // Sort matching Python logic
            docList.sort((a, b) -> {
                boolean aIndexed = (Boolean) a.get("indexed") || a.get("covered_by") != null;
                boolean bIndexed = (Boolean) b.get("indexed") || b.get("covered_by") != null;
                
                if (aIndexed != bIndexed) {
                    return Boolean.compare(aIndexed, bIndexed); // non-indexed first (in reverse sort)
                }
                
                long aMod = (Long) a.get("modified");
                long bMod = (Long) b.get("modified");
                return Long.compare(bMod, aMod); // newest first
            });

            return Map.of("documents", docList);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> upload(@RequestPart("files") Flux<FilePart> files) {
        Path dataDir = Paths.get(appConfig.getQdrant().getDataPath());
        List<String> allowedTypes = appConfig.getQdrant().getAllowKnowledgeFileTypes();
        long maxBytes = 50L * 1024 * 1024; // 50MB limit

        List<String> saved = new ArrayList<>();
        List<Path> savedPaths = new ArrayList<>();
        List<Map<String, String>> errors = new ArrayList<>();

        return files.concatMap(filePart -> {
            String filename = filePart.filename();
            if (filename == null || filename.isEmpty()) {
                errors.add(Map.of("name", "(unknown)", "error", "Invalid filename"));
                return Mono.empty();
            }

            String ext = "";
            int dot = filename.lastIndexOf('.');
            if (dot > 0) {
                ext = filename.substring(dot + 1).toLowerCase();
            }

            if (!allowedTypes.contains(ext)) {
                errors.add(Map.of("name", filename, "error", "Unsupported file type: ." + ext));
                return Mono.empty();
            }

            Path targetPath = getSafeDocumentPath(filename, dataDir);

            return Mono.fromCallable(() -> {
                        Files.createDirectories(targetPath.getParent());
                        return Files.createTempFile(
                                targetPath.getParent(),
                                ".upload-",
                                "-" + targetPath.getFileName()
                        );
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(tempPath -> filePart.transferTo(tempPath)
                            .then(Mono.fromCallable(() -> {
                                if (Files.size(tempPath) > maxBytes) {
                                    throw new IOException("File exceeds 50 MB limit");
                                }
                                moveUploadIntoPlace(tempPath, targetPath);
                                saved.add(filename);
                                savedPaths.add(targetPath.toAbsolutePath().normalize());
                                return filename;
                            }).subscribeOn(Schedulers.boundedElastic()))
                            .onErrorResume(e -> {
                                try {
                                    Files.deleteIfExists(tempPath);
                                } catch (IOException cleanupError) {
                                    log.warn("Failed to remove temporary upload {}", tempPath, cleanupError);
                                }
                                String message = e.getMessage() == null
                                        ? e.getClass().getSimpleName()
                                        : e.getMessage();
                                errors.add(Map.of("name", filename, "error", message));
                                return Mono.empty();
                            }));
        })
        .then(Mono.fromCallable(() -> {
            boolean trainingStarted = false;
            if (!saved.isEmpty()) {
                semanticCacheService.clear();
                trainingStarted = indexingService.startTraining(List.copyOf(savedPaths));
            }
            
            Map<String, Object> res = new HashMap<>();
            res.put("saved", saved);
            res.put("errors", errors);
            res.put("training_started", trainingStarted);
            return res;
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    private void moveUploadIntoPlace(Path tempPath, Path targetPath) throws IOException {
        try {
            Files.move(
                    tempPath,
                    targetPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (IOException atomicMoveError) {
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @DeleteMapping("/documents/{*filename}")
    public Mono<Map<String, Object>> deleteDocument(@PathVariable String filename) {
        return Mono.fromCallable(() -> {
            Path dataDir = Paths.get(appConfig.getQdrant().getDataPath());
            Path filePath = getSafeDocumentPath(filename, dataDir);
            
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
            }

            String ext = "";
            String name = filePath.getFileName().toString();
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                ext = name.substring(dot + 1).toLowerCase();
            }

            List<String> allowedTypes = appConfig.getQdrant().getAllowKnowledgeFileTypes();
            if (!allowedTypes.contains(ext)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File type not allowed");
            }

            Path quarantinedPath = quarantineDocument(filePath);
            ManifestData manifest = manifestManager.loadManifest();
            boolean wasIndexed = manifest.getDocuments().containsKey(manifestManager.stableFileDocId(filePath));
            boolean success;
            try {
                success = vectorStoreService.deleteDocumentByPath(filePath.toString());
                if (wasIndexed && !success) {
                    restoreQuarantinedDocument(quarantinedPath, filePath);
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Document was not deleted because vector index cleanup failed"
                    );
                }
            } catch (RuntimeException error) {
                restoreQuarantinedDocument(quarantinedPath, filePath);
                throw error;
            }

            try {
                Files.deleteIfExists(quarantinedPath);
            } catch (IOException cleanupError) {
                log.warn("Document was deleted but its quarantined file could not be removed: {}",
                        quarantinedPath, cleanupError);
            }
            semanticCacheService.clear();

            Map<String, Object> res = new HashMap<>();
            res.put("deleted", filename);
            res.put("vector_delete_ok", success);
            return res;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/document-chunks")
    public Mono<Map<String, Object>> documentChunks(
            @RequestParam String filename,
            @RequestParam(defaultValue = "200") int limit) {
        return Mono.fromCallable(() -> {
            Path dataDir = Paths.get(appConfig.getQdrant().getDataPath());
            Path filePath = getSafeDocumentPath(filename, dataDir);
            if (!Files.isRegularFile(filePath)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
            }
            List<Map<String, Object>> chunks = vectorStoreService.getDocumentChunks(filePath, limit);
            return Map.of(
                    "filename", filename,
                    "chunks", chunks,
                    "count", chunks.size()
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Path quarantineDocument(Path filePath) throws IOException {
        Path quarantinePath = filePath.resolveSibling(
                "." + filePath.getFileName() + "." + UUID.randomUUID() + ".deleting"
        );
        try {
            return Files.move(filePath, quarantinePath, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveError) {
            return Files.move(filePath, quarantinePath);
        }
    }

    private void restoreQuarantinedDocument(Path quarantinePath, Path filePath) {
        if (!Files.exists(quarantinePath)) {
            return;
        }
        try {
            try {
                Files.move(quarantinePath, filePath, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveError) {
                Files.move(quarantinePath, filePath);
            }
        } catch (IOException restoreError) {
            throw new IllegalStateException("Failed to restore document after index cleanup failure", restoreError);
        }
    }

    @GetMapping("/training/status")
    public Map<String, Object> trainingStatus() {
        return indexingService.getTrainingState();
    }

    @PostMapping("/training/start")
    public Map<String, Object> trainingStart() {
        boolean started = indexingService.startTraining();
        return Map.of(
                "started", started,
                "message", started ? "Training started" : "Training already running — marked as pending"
        );
    }
}
