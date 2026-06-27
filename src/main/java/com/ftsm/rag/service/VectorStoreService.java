package com.ftsm.rag.service;

import com.ftsm.rag.config.AppConfig;
import com.ftsm.rag.model.IndexUpdateResult;
import com.ftsm.rag.model.ManifestData;
import com.ftsm.rag.model.ManifestRecord;
import com.ftsm.rag.store.DocumentManifestManager;
import com.ftsm.rag.utils.FileExtractors;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VectorStoreService {

    private final AppConfig appConfig;
    private final ModelFactory modelFactory;
    private final DocumentManifestManager manifestManager;
    private final FileExtractors fileExtractors;
    private final LocalQdrantManager localQdrantManager;
    private final LexicalIndexService lexicalIndexService;

    private final AtomicReference<QdrantClient> clientRef = new AtomicReference<>();
    private final ReentrantLock indexMutationLock = new ReentrantLock();

    /**
     * Project-managed executor for blocking I/O (Qdrant RPC, embedding calls,
     * Lucene directory access). Uses a bounded queue to prevent unbounded
     * memory growth under load.
     */
    private final ExecutorService retrievalExecutor = new ThreadPoolExecutor(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            Math.max(4, Runtime.getRuntime().availableProcessors() * 2),
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            runnable -> {
                Thread thread = new Thread(runnable, "ftsm-java-retrieval");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    private final String collectionName;
    private final Map<String, Object> indexConfig;
    private final String indexFingerprint;

    /** Names of seed documents that must be present after a full rebuild. */
    private static final Set<String> REQUIRED_SEED_DOCUMENTS = Set.of(
            "ftsm_official_website.txt"
    );

    public VectorStoreService(AppConfig appConfig, ModelFactory modelFactory,
                              DocumentManifestManager manifestManager, FileExtractors fileExtractors,
                              LocalQdrantManager localQdrantManager,
                              LexicalIndexService lexicalIndexService) {
        this.appConfig = appConfig;
        this.modelFactory = modelFactory;
        this.manifestManager = manifestManager;
        this.fileExtractors = fileExtractors;
        this.localQdrantManager = localQdrantManager;
        this.lexicalIndexService = lexicalIndexService;
        this.collectionName = appConfig.getQdrant().getCollectionName();
        this.indexConfig = buildIndexConfig();
        this.indexFingerprint = buildIndexFingerprint(indexConfig);
    }

    @jakarta.annotation.PreDestroy
    void shutdownRetrievalExecutor() {
        retrievalExecutor.shutdown();
        try {
            if (!retrievalExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                retrievalExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            retrievalExecutor.shutdownNow();
        }
    }

    private Map<String, Object> buildIndexConfig() {
        Map<String, Object> config = new TreeMap<>();
        config.put("schema_version", 4);
        config.put("embedding_model_name", appConfig.getVertexai().getEmbeddingModel());
        config.put("collection_name", collectionName);
        config.put("chunk_size", appConfig.getQdrant().getChunkSize());
        config.put("chunk_overlap", appConfig.getQdrant().getChunkOverlap());
        config.put("splitter", "python-recursive-character-v1");
        config.put("separators", PythonCompatibleTextSplitter.DEFAULT_SEPARATORS);
        config.put("lexical_index", "lucene-bm25-cjk-9.12.3");
        config.put("allowed_file_types", appConfig.getQdrant().getAllowKnowledgeFileTypes());
        return config;
    }

    static String buildIndexFingerprint(Map<String, Object> config) {
        try {
            String canonical = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(config);
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                result.append(String.format("%02x", hash[i]));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build index fingerprint", e);
        }
    }

    /**
     * Returns a connected Qdrant client. Never creates a collection.
     * Callers that need a collection must create it explicitly.
     */
    private QdrantClient getClient() {
        QdrantClient client = clientRef.get();
        if (client == null) {
            synchronized (clientRef) {
                client = clientRef.get();
                if (client == null) {
                    AppConfig.QdrantProperties props = appConfig.getQdrant();
                    localQdrantManager.ensureRunning();
                    log.info("Connecting to Qdrant server at {}:{}", props.getHost(), props.getPort());

                    QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(props.getHost(), props.getPort(), props.isUseTls());
                    if (props.getApiKey() != null && !props.getApiKey().isEmpty()) {
                        builder.withApiKey(props.getApiKey());
                    }

                    client = new QdrantClient(builder.build());
                    clientRef.set(client);
                }
            }
        }
        return client;
    }

    /**
     * Creates the named collection after probing the embedding dimension.
     * Throws if the dimension cannot be determined.
     */
    private void createCollectionWithProbedDimension(String targetCollection) throws Exception {
        QdrantClient client = getClient();
        int vectorSize;
        try {
            vectorSize = modelFactory.getEmbeddingModel()
                    .embed("dimension probe")
                    .content()
                    .vector()
                    .length;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to determine the embedding vector dimension from the configured "
                            + "model; refusing to create a collection with a guessed size. "
                            + "Underlying error: " + e.getMessage(),
                    e);
        }
        log.info("Creating collection {} with verified dimension {}.", targetCollection, vectorSize);
        client.createCollectionAsync(targetCollection,
                io.qdrant.client.grpc.Collections.VectorParams.newBuilder()
                        .setSize(vectorSize)
                        .setDistance(io.qdrant.client.grpc.Collections.Distance.Cosine)
                        .build()
        ).get();
        log.info("Collection {} created successfully", targetCollection);
    }

    public List<Document> search(String query, int k) {
        Set<String> currentChunkIds = currentManifestChunkIds();
        if (currentChunkIds.isEmpty()) {
            return Collections.emptyList();
        }
        int limit = Math.max(k, appConfig.getQdrant().getHybridSearchLimit());
        String activeCollection = activeCollectionName();
        String lexicalGeneration = activeLexicalGeneration();

        long timeoutMillis = appConfig.getQdrant().getHybridSearchTimeoutMs() > 0
                ? appConfig.getQdrant().getHybridSearchTimeoutMs()
                : 15_000;

        CompletableFuture<List<Document>> denseFuture = CompletableFuture.supplyAsync(
                () -> denseSearch(activeCollection, query, limit), retrievalExecutor);
        CompletableFuture<List<Document>> lexicalFuture = CompletableFuture.supplyAsync(
                () -> lexicalIndexService.search(lexicalGeneration, query, limit), retrievalExecutor);

        List<Document> dense = safeGet(denseFuture, timeoutMillis, "dense");
        List<Document> lexical = safeGet(lexicalFuture, timeoutMillis, "lexical");

        dense = filterToCurrentChunks(dense, currentChunkIds);
        lexical = filterToCurrentChunks(lexical, currentChunkIds);

        if (dense.isEmpty() && lexical.isEmpty()) {
            return Collections.emptyList();
        }
        if (dense.isEmpty()) {
            return lexical.stream().limit(k).collect(Collectors.toList());
        }
        if (lexical.isEmpty()) {
            return dense.stream().limit(k).collect(Collectors.toList());
        }

        Map<String, Double> scores = new HashMap<>();
        Map<String, Document> documents = new HashMap<>();
        addRrfScores(dense, scores, documents);
        addRrfScores(lexical, scores, documents);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(k)
                .map(entry -> documents.get(entry.getKey()))
                .collect(Collectors.toList());
    }

    private static <T> List<T> safeGet(CompletableFuture<List<T>> future, long timeoutMillis, String legName) {
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Hybrid retrieval leg '{}' timed out after {}ms", legName, timeoutMillis);
            future.cancel(true);
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("Hybrid retrieval leg '{}' failed: {}", legName, e.getMessage());
            future.cancel(true);
            return Collections.emptyList();
        }
    }

    private static List<Document> filterToCurrentChunks(
            List<Document> documents, Set<String> currentChunkIds) {
        if (documents == null || documents.isEmpty() || currentChunkIds.isEmpty()) {
            return Collections.emptyList();
        }
        return documents.stream()
                .filter(document -> currentChunkIds.contains(document.metadata().getString("chunk_id")))
                .collect(Collectors.toList());
    }

    private List<Document> denseSearch(String targetCollection, String query, int limit) {
        try {
            QdrantClient client = getClient();
            Embedding embedding = modelFactory.getEmbeddingModel().embed(query).content();
            List<Float> queryVector = new ArrayList<>();
            for (float value : embedding.vector()) {
                queryVector.add(value);
            }
            SearchPoints searchRequest = SearchPoints.newBuilder()
                    .setCollectionName(targetCollection)
                    .addAllVector(queryVector)
                    .setLimit(limit)
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                    .build();

            List<ScoredPoint> results = client.searchAsync(searchRequest).get();
            List<Document> docs = new ArrayList<>();
            for (ScoredPoint point : results) {
                docs.add(documentFromPayload(point.getPayloadMap()));
            }
            return docs;
        } catch (Exception e) {
            log.warn("Dense search failed; continuing with lexical retrieval: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Document> scrollAllDocuments(String targetCollection) throws Exception {
        QdrantClient client = getClient();
        List<Document> documents = new ArrayList<>();
        PointId offset = null;
        do {
            ScrollPoints.Builder request = ScrollPoints.newBuilder()
                    .setCollectionName(targetCollection)
                    .setLimit(256)
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build());
            if (offset != null) {
                request.setOffset(offset);
            }
            ScrollResponse response = client.scrollAsync(request.build()).get();
            for (RetrievedPoint point : response.getResultList()) {
                Document document = documentFromPayload(point.getPayloadMap());
                if (!document.text().isBlank()) {
                    documents.add(document);
                }
            }
            offset = response.hasNextPageOffset() ? response.getNextPageOffset() : null;
        } while (offset != null);
        return documents;
    }

    /**
     * Retrieves the full Qdrant points (payload + vector) for the given chunk IDs.
     */
    private List<RetrievedPoint> retrievePoints(String targetCollection, List<String> chunkIds) throws Exception {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return Collections.emptyList();
        }
        QdrantClient client = getClient();
        List<PointId> pointIds = chunkIds.stream()
                .map(id -> PointIdFactory.id(UUID.fromString(id)))
                .collect(Collectors.toList());
        return client.retrieveAsync(
                targetCollection,
                pointIds,
                WithPayloadSelector.newBuilder().setEnable(true).build(),
                WithVectorsSelector.newBuilder().setEnable(true).build(),
                null,
                Duration.ofSeconds(30)
        ).get();
    }

    private String activeCollectionName() {
        ManifestData manifest = manifestManager.loadManifest();
        if (manifest.getIndex() != null
                && manifest.getIndex().getQdrantCollection() != null
                && !manifest.getIndex().getQdrantCollection().isBlank()) {
            return manifest.getIndex().getQdrantCollection();
        }
        return collectionName;
    }

    private String activeLexicalGeneration() {
        ManifestData manifest = manifestManager.loadManifest();
        return manifest.getIndex() == null ? null : manifest.getIndex().getLexicalGeneration();
    }

    public List<Map<String, Object>> getDocumentChunks(Path path, int requestedLimit) {
        String docId = manifestManager.stableFileDocId(path);
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        ManifestRecord record = manifestManager.loadManifest().getDocuments().get(docId);
        if (record == null || record.getChunkIds() == null) {
            return Collections.emptyList();
        }
        Set<String> currentChunkIds = new HashSet<>(record.getChunkIds());
        try {
            List<Document> lexicalDocuments = lexicalIndexService.findByDocumentId(
                    activeLexicalGeneration(), docId, limit);
            List<Document> documents = lexicalDocuments.isEmpty()
                    ? scrollAllDocuments(activeCollectionName())
                    : lexicalDocuments;
            return documents.stream()
                    .filter(document -> docId.equals(document.metadata().getString("doc_id")))
                    .filter(document -> currentChunkIds.contains(document.metadata().getString("chunk_id")))
                    .sorted(Comparator.comparingInt(this::chunkIndex))
                    .limit(limit)
                    .map(document -> {
                        Map<String, Object> chunk = new LinkedHashMap<>();
                        chunk.put("chunk_id", document.metadata().getString("chunk_id"));
                        chunk.put("chunk_index", chunkIndex(document));
                        chunk.put("text", document.text());
                        chunk.put("source_type", document.metadata().getString("source_type"));
                        chunk.put("source_trust_label",
                                document.metadata().getString("source_trust_label"));
                        return chunk;
                    })
                    .collect(Collectors.toList());
        } catch (Exception error) {
            throw new IllegalStateException("Failed to load document chunks", error);
        }
    }

    private int chunkIndex(Document document) {
        String value = document.metadata().getString("chunk_index");
        try {
            return value == null ? Integer.MAX_VALUE : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private Document documentFromPayload(Map<String, Value> payload) {
        String text = payload.containsKey("page_content")
                ? payload.get("page_content").getStringValue()
                : payload.getOrDefault("text", Value.getDefaultInstance()).getStringValue();
        Metadata metadata = new Metadata();
        for (Map.Entry<String, Value> entry : payload.entrySet()) {
            Value value = entry.getValue();
            if (value.hasStringValue()) {
                metadata.put(entry.getKey(), value.getStringValue());
            } else if (value.hasIntegerValue()) {
                metadata.put(entry.getKey(), String.valueOf(value.getIntegerValue()));
            } else if (value.hasDoubleValue()) {
                metadata.put(entry.getKey(), String.valueOf(value.getDoubleValue()));
            } else if (value.hasBoolValue()) {
                metadata.put(entry.getKey(), String.valueOf(value.getBoolValue()));
            }
        }
        return Document.from(text, metadata);
    }

    private void addRrfScores(List<Document> ranked, Map<String, Double> scores,
                              Map<String, Document> documents) {
        for (int i = 0; i < ranked.size(); i++) {
            Document document = ranked.get(i);
            String key = document.metadata().getString("chunk_id");
            if (key == null || key.isBlank()) {
                key = document.text();
            }
            scores.merge(key, 1.0 / (60.0 + i + 1), Double::sum);
            documents.putIfAbsent(key, document);
        }
    }

    static List<String> tokenize(String text) {
        String normalized = normalizeText(text);
        List<String> terms = new ArrayList<>();
        Matcher latin = Pattern.compile("[a-z0-9]+").matcher(normalized);
        while (latin.find()) {
            terms.add(latin.group());
        }
        Matcher chinese = Pattern.compile("[\\u4e00-\\u9fff]+").matcher(normalized);
        while (chinese.find()) {
            String group = chinese.group();
            if (group.length() <= 2) {
                terms.add(group);
            } else {
                for (int i = 0; i < group.length() - 1; i++) {
                    terms.add(group.substring(i, i + 2));
                }
            }
        }
        return terms;
    }

    private static String normalizeText(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    // ==================== DELETE WITH FULL ROLLBACK ====================

    public boolean deleteDocumentByPath(String filePathStr) {
        indexMutationLock.lock();
        try {
            return deleteDocumentByPathLocked(filePathStr);
        } finally {
            indexMutationLock.unlock();
        }
    }

    /**
     * Unified delete transaction: backup -> delete -> commit.
     * Any stage failure triggers rollback of all three stores.
     */
    private boolean deleteDocumentByPathLocked(String filePathStr) {
        Path path = Paths.get(filePathStr);
        String docId = manifestManager.stableFileDocId(path);

        ManifestData manifest = manifestManager.loadManifest();
        ManifestRecord record = manifest.getDocuments().get(docId);
        if (record == null) {
            log.info("[knowledge delete] No manifest record found for {}", docId);
            return false;
        }

        String targetCollection = activeCollectionName();
        String lexicalGeneration = activeLexicalGeneration();
        List<String> chunkIds = record.getChunkIds();

        // Phase 1: Backup all three stores
        List<RetrievedPoint> qdrantBackup;
        List<Document> lexicalBackup;
        ManifestRecord manifestBackup = record;
        try {
            qdrantBackup = retrievePoints(targetCollection, chunkIds);
            lexicalBackup = lexicalIndexService.findByDocumentId(
                    lexicalGeneration, docId, Math.max(chunkIds.size(), 1));
        } catch (Exception e) {
            log.error("[knowledge delete] Failed to backup old index state for {}: {}", docId, e.getMessage());
            manifestManager.updateManifestIndexState(manifest,
                    "Delete backup failed for " + docId + ": " + e.getMessage(), false, null);
            manifestManager.saveManifest(manifest);
            return false;
        }

        // Phase 2: Execute deletion
        boolean manifestDeleted = false;
        boolean luceneDeleted = false;
        boolean qdrantDeleted = false;
        try {
            manifest.getDocuments().remove(docId);
            manifestManager.saveManifest(manifest);
            manifestDeleted = true;

            lexicalIndexService.deleteDocument(lexicalGeneration, docId);
            luceneDeleted = true;

            deleteChunks(targetCollection, chunkIds);
            qdrantDeleted = true;

            log.info("[knowledge delete] Removed {} from all three stores", docId);
            return true;
        } catch (Exception e) {
            log.error("[knowledge delete] Delete failed for {}, rolling back: {}", docId, e.getMessage());
            // Phase 3: Rollback
            rollbackDelete(manifest, docId, manifestBackup, lexicalGeneration, lexicalBackup,
                    targetCollection, qdrantBackup, manifestDeleted, luceneDeleted, qdrantDeleted);
            return false;
        }
    }

    private void rollbackDelete(ManifestData manifest, String docId, ManifestRecord manifestBackup,
                                String lexicalGeneration, List<Document> lexicalBackup,
                                String targetCollection, List<RetrievedPoint> qdrantBackup,
                                boolean manifestDeleted, boolean luceneDeleted, boolean qdrantDeleted) {
        List<String> rollbackErrors = new ArrayList<>();
        if (manifestDeleted) {
            try {
                manifest.getDocuments().put(docId, manifestBackup);
                manifestManager.saveManifest(manifest);
            } catch (Exception e) {
                rollbackErrors.add("manifest restore failed: " + e.getMessage());
            }
        }
        if (luceneDeleted) {
            try {
                lexicalIndexService.replaceDocument(lexicalGeneration, docId, lexicalBackup);
            } catch (Exception e) {
                rollbackErrors.add("lucene restore failed: " + e.getMessage());
            }
        }
        if (qdrantDeleted && !qdrantBackup.isEmpty()) {
            try {
                List<PointStruct> restorePoints = qdrantBackup.stream()
                        .map(this::retrievedPointToPointStruct)
                        .collect(Collectors.toList());
                for (int i = 0; i < restorePoints.size(); i += 20) {
                    getClient().upsertAsync(targetCollection,
                            restorePoints.subList(i, Math.min(i + 20, restorePoints.size()))).get();
                }
            } catch (Exception e) {
                rollbackErrors.add("qdrant restore failed: " + e.getMessage());
            }
        }
        if (!rollbackErrors.isEmpty()) {
            String msg = "DELETE ROLLBACK PARTIALLY FAILED for " + docId + ": " + String.join("; ", rollbackErrors);
            log.error(msg);
            manifestManager.updateManifestIndexState(manifest, msg, false, null);
            manifestManager.saveManifest(manifest);
            throw new IllegalStateException(msg);
        }
    }

    private PointStruct retrievedPointToPointStruct(RetrievedPoint point) {
        List<Float> vector = point.getVectors().getVector().getDataList();
        return PointStruct.newBuilder()
                .setId(point.getId())
                .setVectors(Vectors.newBuilder()
                        .setVector(io.qdrant.client.grpc.Points.Vector.newBuilder().addAllData(vector).build())
                        .build())
                .putAllPayload(point.getPayloadMap())
                .build();
    }

    private boolean deleteChunks(String targetCollection, List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return true;
        }
        QdrantClient client = getClient();
        List<PointId> pointIds = chunkIds.stream()
                .map(id -> PointIdFactory.id(UUID.fromString(id)))
                .collect(Collectors.toList());

        try {
            client.deleteAsync(targetCollection, pointIds).get();
            log.info("[knowledge load] Deleted {} chunks from Qdrant", chunkIds.size());
            return true;
        } catch (Exception e) {
            log.error("[knowledge load] Failed to delete chunks from Qdrant", e);
            return false;
        }
    }

    // ==================== INCREMENTAL INDEX ====================

    public Map<String, Object> loadDocumentsIncremental(List<Path> targetPaths) {
        indexMutationLock.lock();
        try {
            return loadDocumentsIncrementalLocked(targetPaths);
        } finally {
            indexMutationLock.unlock();
        }
    }

    private Map<String, Object> loadDocumentsIncrementalLocked(List<Path> targetPaths) {
        if (targetPaths == null) {
            return rebuildAllDocumentsLocked();
        }
        IndexUpdateResult result = applyIncrementalUpdatesLocked(targetPaths);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", result.isSuccess());
        map.put("modified", result.isModified());
        map.put("consistent", result.isConsistent());
        map.put("errors", result.getErrors());
        map.put("error_summary", result.getErrorSummary());
        map.put("document_count", result.getDocumentCount());
        map.put("total_chunks", result.getTotalChunks());
        return map;
    }

    /**
     * Applies the Python "load_document(target_paths=[...])" semantics: index
     * only the requested files, syncing manifest / Qdrant / Lucene in lock-step
     * with a per-file backup + rollback. Does NOT scan the data directory.
     */
    private IndexUpdateResult applyIncrementalUpdatesLocked(List<Path> targetPaths) {
        ManifestData manifest = manifestManager.loadManifest();
        String targetCollection = activeCollectionName();
        String lexicalGeneration = activeLexicalGeneration();
        if (lexicalGeneration == null || lexicalGeneration.isBlank()) {
            return IndexUpdateResult.failed(
                    List.of("The lexical index has not been initialized; run a full re-index first"), true);
        }
        List<String> allowedTypes = appConfig.getQdrant().getAllowKnowledgeFileTypes();

        List<Path> allowedFiles = targetPaths.stream()
                .filter(p -> {
                    String name = p.getFileName().toString();
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) {
                        return allowedTypes.contains(name.substring(dot + 1).toLowerCase());
                    }
                    return false;
                })
                .collect(Collectors.toList());

        IndexUpdateResult result = indexFiles(manifest, targetCollection, lexicalGeneration, allowedFiles);

        if (result.isModified()) {
            manifestManager.updateManifestIndexState(manifest, result.getErrorSummary(), true, indexFingerprint);
        } else {
            manifestManager.updateManifestIndexState(manifest, result.getErrorSummary(), false, null);
        }
        manifestManager.saveManifest(manifest);

        return result.toBuilder()
                .newVersion(manifest.getIndex().getVersion())
                .build();
    }

    /**
     * Reconciles "files removed from disk" with the durable index.
     * This is an explicit entry point for incremental sync that removes
     * orphan chunks from deleted source files.
     */
    public IndexUpdateResult syncMissingFiles() {
        indexMutationLock.lock();
        try {
            ManifestData manifest = manifestManager.loadManifest();
            String targetCollection = activeCollectionName();
            String lexicalGeneration = activeLexicalGeneration();
            if (lexicalGeneration == null || lexicalGeneration.isBlank()) {
                return IndexUpdateResult.failed(
                        List.of("The lexical index has not been initialized; run a full re-index first"), true);
            }

            Set<String> currentDocIds;
            try {
                currentDocIds = listEffectiveKnowledgeFiles().stream()
                        .map(manifestManager::stableFileDocId)
                        .collect(Collectors.toSet());
            } catch (IOException e) {
                return IndexUpdateResult.failed(List.of("Failed to list knowledge files: " + e.getMessage()), true);
            }

            List<String> removed = reconcileMissingFiles(manifest, targetCollection, lexicalGeneration, currentDocIds);

            boolean modified = !removed.isEmpty();
            if (modified) {
                manifestManager.updateManifestIndexState(manifest, null, true, indexFingerprint);
                manifestManager.saveManifest(manifest);
            }

            return IndexUpdateResult.builder()
                    .success(true)
                    .modified(modified)
                    .consistent(true)
                    .errors(Collections.emptyList())
                    .documentCount(manifest.getDocuments().size())
                    .totalChunks(manifest.getDocuments().values().stream().mapToLong(r -> r.getChunkIds().size()).sum())
                    .build();
        } finally {
            indexMutationLock.unlock();
        }
    }

    private List<String> reconcileMissingFiles(
            ManifestData manifest, String targetCollection, String lexicalGeneration,
            Set<String> currentDocIds) {
        List<String> removed = new ArrayList<>();
        List<String> toRemove = new ArrayList<>();
        for (String docId : manifest.getDocuments().keySet()) {
            if (docId.startsWith("file:") && !currentDocIds.contains(docId)) {
                toRemove.add(docId);
            }
        }
        for (String docId : toRemove) {
            ManifestRecord record = manifest.getDocuments().get(docId);
            if (record == null) {
                continue;
            }
            // Use the same transactional delete logic
            boolean ok = transactionalDeleteSingleDocument(
                    manifest, targetCollection, lexicalGeneration, docId, record);
            if (ok) {
                removed.add(docId);
                log.info("[knowledge sync] Cleaned up missing document {}", docId);
            } else {
                log.warn("[knowledge sync] Failed to clean up missing document {}", docId);
            }
        }
        return removed;
    }

    private boolean transactionalDeleteSingleDocument(ManifestData manifest, String targetCollection,
                                                       String lexicalGeneration, String docId,
                                                       ManifestRecord record) {
        List<String> chunkIds = record.getChunkIds();
        List<RetrievedPoint> qdrantBackup;
        List<Document> lexicalBackup;
        try {
            qdrantBackup = retrievePoints(targetCollection, chunkIds);
            lexicalBackup = lexicalIndexService.findByDocumentId(
                    lexicalGeneration, docId, Math.max(chunkIds.size(), 1));
        } catch (Exception e) {
            log.warn("Failed to backup before deleting orphan {}: {}", docId, e.getMessage());
            return false;
        }

        boolean manifestDeleted = false;
        try {
            manifest.getDocuments().remove(docId);
            manifestManager.saveManifest(manifest);
            manifestDeleted = true;
            lexicalIndexService.deleteDocument(lexicalGeneration, docId);
            deleteChunks(targetCollection, chunkIds);
            return true;
        } catch (Exception e) {
            if (manifestDeleted) {
                try {
                    manifest.getDocuments().put(docId, record);
                    manifestManager.saveManifest(manifest);
                } catch (Exception re) {
                    log.error("Failed to restore manifest for orphan {}", docId, re);
                }
            }
            return false;
        }
    }

    // ==================== INDEX FILES WITH FULL ROLLBACK ====================

    private IndexUpdateResult indexFiles(
            ManifestData manifest, String targetCollection, String lexicalGeneration,
            List<Path> allowedFiles) {
        List<String> errors = new ArrayList<>();
        boolean anyModified = false;
        boolean anyInconsistent = false;

        for (Path p : allowedFiles) {
            PerFileTransaction tx = new PerFileTransaction();
            try {
                String docId = manifestManager.stableFileDocId(p);
                tx.docId = docId;
                String hash = FileExtractors.getFileSha256Hex(p);
                DocumentManifestManager.SourceClassification classification = manifestManager.classifySource(p);

                ManifestRecord previous = manifest.getDocuments().get(docId);
                Set<String> previousChunkIds = previous == null
                        ? Collections.emptySet()
                        : new HashSet<>(previous.getChunkIds());
                tx.previous = previous;

                // Backup old Qdrant points and Lucene documents
                if (!previousChunkIds.isEmpty()) {
                    tx.qdrantBackup = retrievePoints(targetCollection, new ArrayList<>(previousChunkIds));
                    tx.lexicalBackup = lexicalIndexService.findByDocumentId(
                            lexicalGeneration, docId, previousChunkIds.size());
                }

                boolean unchanged = previous != null
                        && Objects.equals(previous.getHash(), hash)
                        && Objects.equals(previous.getSourceType(), classification.getType())
                        && Objects.equals(previous.getIndexFingerprint(), indexFingerprint);

                if (unchanged) {
                    log.info("[knowledge load] {} is unchanged, skipping.", p.getFileName());
                    continue;
                }

                // Load document content
                List<Document> rawDocs = loadFileContent(p).block();
                if (rawDocs == null || rawDocs.isEmpty()) {
                    errors.add(p.getFileName() + ": no valid text found");
                    continue;
                }

                // Chunking
                List<Document> chunkDocs = splitDocuments(rawDocs);
                if (chunkDocs.isEmpty()) {
                    errors.add(p.getFileName() + ": no valid chunks produced");
                    continue;
                }

                // Build points and embeddings
                List<String> chunkIds = new ArrayList<>();
                List<PointStruct> points = new ArrayList<>();
                List<Document> lexicalDocuments = new ArrayList<>();

                List<TextSegment> segments = chunkDocs.stream()
                        .map(chunk -> TextSegment.from(chunk.text()))
                        .collect(Collectors.toList());

                log.info("[knowledge load] Generating embeddings for {} chunks of {}", segments.size(), p.getFileName());
                List<Embedding> embeddings = embedInBatches(segments);

                for (int i = 0; i < chunkDocs.size(); i++) {
                    Document chunk = chunkDocs.get(i);
                    String chunkId = pythonCompatibleChunkId(docId, i);
                    chunkIds.add(chunkId);

                    Map<String, Value> payload = buildPayload(p, chunk, docId, chunkId, i, hash,
                            manifestManager.getUtcIsoNow(), classification);
                    points.add(pointFromPayload(chunkId, embeddings.get(i), payload));
                    lexicalDocuments.add(documentFromPayload(payload));
                }

                // Upsert to Qdrant in batches
                QdrantClient client = getClient();
                for (int i = 0; i < points.size(); i += 20) {
                    List<PointStruct> batch = points.subList(i, Math.min(i + 20, points.size()));
                    client.upsertAsync(targetCollection, batch).get();
                }
                tx.qdrantUpserted = true;

                // Update Lucene
                lexicalIndexService.replaceDocument(lexicalGeneration, docId, lexicalDocuments);
                tx.lexicalUpdated = true;

                // Commit manifest
                ManifestRecord record = buildManifestRecord(p, docId, hash,
                        manifestManager.getUtcIsoNow(), classification, chunkIds);
                manifest.getDocuments().put(docId, record);
                manifestManager.saveManifest(manifest);
                tx.manifestCommitted = true;

                // Delete stale previous chunks
                List<String> stalePreviousChunks = previous == null
                        ? Collections.emptyList()
                        : previous.getChunkIds().stream()
                                .filter(oldId -> !chunkIds.contains(oldId))
                                .collect(Collectors.toList());
                if (!stalePreviousChunks.isEmpty()) {
                    deleteChunks(targetCollection, stalePreviousChunks);
                }

                anyModified = true;
                log.info("[knowledge load] Indexed {} chunks from {}", chunkIds.size(), p.getFileName());

            } catch (Exception e) {
                errors.add(p.getFileName() + ": " + e.getMessage());
                log.error("Failed to load document {}", p, e);
                rollbackPerFileTransaction(tx, manifest, targetCollection, lexicalGeneration);
                if (tx.manifestCommitted || tx.qdrantUpserted || tx.lexicalUpdated) {
                    anyInconsistent = true;
                }
            }
        }

        boolean success = errors.isEmpty();
        boolean consistent = !anyInconsistent;
        return IndexUpdateResult.builder()
                .success(success)
                .modified(anyModified)
                .consistent(consistent)
                .errors(errors)
                .errorSummary(errors.isEmpty() ? null : String.join("; ", errors))
                .documentCount(manifest.getDocuments().size())
                .totalChunks(manifest.getDocuments().values().stream().mapToLong(r -> r.getChunkIds().size()).sum())
                .build();
    }

    private static class PerFileTransaction {
        String docId;
        ManifestRecord previous;
        List<RetrievedPoint> qdrantBackup = Collections.emptyList();
        List<Document> lexicalBackup = Collections.emptyList();
        boolean qdrantUpserted = false;
        boolean lexicalUpdated = false;
        boolean manifestCommitted = false;
    }

    private void rollbackPerFileTransaction(PerFileTransaction tx, ManifestData manifest,
                                            String targetCollection, String lexicalGeneration) {
        if (tx.docId == null) {
            return;
        }
        List<String> rollbackErrors = new ArrayList<>();

        // 1. Restore manifest
        if (tx.manifestCommitted) {
            try {
                if (tx.previous == null) {
                    manifest.getDocuments().remove(tx.docId);
                } else {
                    manifest.getDocuments().put(tx.docId, tx.previous);
                }
                manifestManager.saveManifest(manifest);
            } catch (Exception e) {
                rollbackErrors.add("manifest restore failed: " + e.getMessage());
            }
        }

        // 2. Restore Lucene
        if (tx.lexicalUpdated) {
            try {
                lexicalIndexService.replaceDocument(lexicalGeneration, tx.docId, tx.lexicalBackup);
            } catch (Exception e) {
                rollbackErrors.add("lucene restore failed: " + e.getMessage());
            }
        }

        // 3. Restore Qdrant (re-upsert old points)
        if (tx.qdrantUpserted && !tx.qdrantBackup.isEmpty()) {
            try {
                List<PointStruct> restorePoints = tx.qdrantBackup.stream()
                        .map(this::retrievedPointToPointStruct)
                        .collect(Collectors.toList());
                for (int i = 0; i < restorePoints.size(); i += 20) {
                    getClient().upsertAsync(targetCollection,
                            restorePoints.subList(i, Math.min(i + 20, restorePoints.size()))).get();
                }
            } catch (Exception e) {
                rollbackErrors.add("qdrant restore failed: " + e.getMessage());
            }
        }

        if (!rollbackErrors.isEmpty()) {
            String msg = "ROLLBACK PARTIALLY FAILED for " + tx.docId + ": " + String.join("; ", rollbackErrors);
            log.error(msg);
            manifestManager.updateManifestIndexState(manifest, msg, false, null);
            try {
                manifestManager.saveManifest(manifest);
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== FULL REBUILD ====================

    /**
     * Full rebuild entry point. Scans the data directory, builds a new Qdrant
     * collection and Lucene generation, validates the three indices agree,
     * then atomically switches the manifest. On any failure, the staging
     * generation is deleted and the previous (still-valid) index continues
     * to serve queries.
     */
    private Map<String, Object> rebuildAllDocumentsLocked() {
        QdrantClient client = getClient();
        ManifestData previousManifest = manifestManager.loadManifest();
        String previousCollection = activeCollectionName();
        String previousLexicalGeneration = activeLexicalGeneration();
        String generation = "g-" + System.currentTimeMillis();
        String targetCollection = collectionName + "_" + generation.replace('-', '_');
        ManifestData stagedManifest = new ManifestData();
        List<Document> lexicalDocuments = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try {
            createCollectionWithProbedDimension(targetCollection);

            Map<String, List<Document>> reusableBinaryChunks =
                    loadReusableBinaryChunks(previousManifest, previousCollection);
            for (Path path : listEffectiveKnowledgeFiles()) {
                try {
                    String docId = manifestManager.stableFileDocId(path);
                    String hash = FileExtractors.getFileSha256Hex(path);
                    DocumentManifestManager.SourceClassification classification =
                            manifestManager.classifySource(path);
                    ManifestRecord previousRecord =
                            previousManifest.getDocuments().get(docId);
                    List<Document> chunks = previousRecord != null
                            && Objects.equals(previousRecord.getHash(), hash)
                            ? reusableBinaryChunks.get(docId)
                            : null;
                    if (chunks == null) {
                        List<Document> rawDocuments = loadFileContent(path).block();
                        chunks = rawDocuments == null
                                ? Collections.emptyList()
                                : splitDocuments(rawDocuments);
                    }
                    if (chunks.isEmpty()) {
                        throw new IllegalStateException("no valid chunks produced");
                    }

                    List<TextSegment> segments = chunks.stream()
                            .map(chunk -> TextSegment.from(chunk.text()))
                            .collect(Collectors.toList());
                    List<Embedding> embeddings = embedInBatches(segments);
                    List<PointStruct> points = new ArrayList<>(chunks.size());
                    List<String> chunkIds = new ArrayList<>(chunks.size());
                    String indexedAt = manifestManager.getUtcIsoNow();

                    for (int index = 0; index < chunks.size(); index++) {
                        String chunkId = pythonCompatibleChunkId(docId, index);
                        chunkIds.add(chunkId);
                        Map<String, Value> payload = buildPayload(
                                path,
                                chunks.get(index),
                                docId,
                                chunkId,
                                index,
                                hash,
                                indexedAt,
                                classification);
                        points.add(pointFromPayload(chunkId, embeddings.get(index), payload));
                        lexicalDocuments.add(documentFromPayload(payload));
                    }
                    upsertBatches(client, targetCollection, points);
                    stagedManifest.getDocuments().put(
                            docId,
                            buildManifestRecord(
                                    path,
                                    docId,
                                    hash,
                                    indexedAt,
                                    classification,
                                    chunkIds));
                    log.info("[knowledge rebuild] Indexed {} chunks from {}",
                            chunks.size(), path.getFileName());
                } catch (Exception error) {
                    errors.add(path.getFileName() + ": " + error.getMessage());
                    log.error("Failed to rebuild document {}", path, error);
                }
            }

            if (!errors.isEmpty()) {
                throw new IllegalStateException(String.join("; ", errors));
            }

            lexicalIndexService.rebuild(generation, lexicalDocuments);
            validateRebuiltIndex(stagedManifest, targetCollection, generation);

            stagedManifest.getIndex().setQdrantCollection(targetCollection);
            stagedManifest.getIndex().setLexicalGeneration(generation);
            stagedManifest.getIndex().setSchemaVersion(4);
            manifestManager.updateManifestIndexState(
                    stagedManifest, null, true, indexFingerprint);
            manifestManager.saveManifest(stagedManifest);

            cleanupPreviousGeneration(
                    client,
                    previousCollection,
                    previousLexicalGeneration,
                    targetCollection,
                    generation);

            Map<String, Object> result = new LinkedHashMap<>();
            long manifestChunks = stagedManifest.getDocuments().values().stream()
                    .mapToLong(record -> record.getChunkIds().size())
                    .sum();
            result.put("success", true);
            result.put("modified", true);
            result.put("consistent", true);
            result.put("errors", Collections.emptyList());
            result.put("error_summary", null);
            result.put("document_count", stagedManifest.getDocuments().size());
            result.put("total_chunks", manifestChunks);
            result.put("qdrant_collection", targetCollection);
            result.put("lexical_generation", generation);
            return result;
        } catch (Exception error) {
            try {
                if (client.listCollectionsAsync().get().contains(targetCollection)) {
                    client.deleteCollectionAsync(targetCollection).get();
                }
            } catch (Exception cleanupError) {
                log.warn("Failed to remove incomplete Qdrant generation {}: {}",
                        targetCollection, cleanupError.getMessage());
            }
            lexicalIndexService.deleteGeneration(generation);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("modified", false);
            result.put("consistent", false);
            result.put("errors", errors.isEmpty()
                    ? List.of(error.getMessage())
                    : errors);
            result.put("error_summary", error.getMessage());
            return result;
        }
    }

    // ==================== VALIDATION ====================

    private void validateRebuiltIndex(ManifestData manifest, String qdrantCollection,
                                       String lexicalGeneration) throws Exception {
        if (manifest.getDocuments().isEmpty()) {
            throw new IllegalStateException("Rebuilt index contains no documents");
        }

        // Collect manifest IDs
        Set<String> manifestIds = new HashSet<>();
        for (ManifestRecord record : manifest.getDocuments().values()) {
            if (record.getChunkIds() != null) {
                manifestIds.addAll(record.getChunkIds());
            }
        }

        // Collect Qdrant IDs
        Set<String> qdrantIds = new HashSet<>();
        QdrantClient client = getClient();
        PointId offset = null;
        do {
            ScrollPoints.Builder request = ScrollPoints.newBuilder()
                    .setCollectionName(qdrantCollection)
                    .setLimit(256)
                    .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build());
            if (offset != null) {
                request.setOffset(offset);
            }
            ScrollResponse response = client.scrollAsync(request.build()).get();
            for (RetrievedPoint point : response.getResultList()) {
                String chunkId = point.getPayloadMap().getOrDefault("chunk_id",
                        Value.getDefaultInstance()).getStringValue();
                if (chunkId != null && !chunkId.isBlank()) {
                    qdrantIds.add(chunkId);
                }
            }
            offset = response.hasNextPageOffset() ? response.getNextPageOffset() : null;
        } while (offset != null);

        // Collect Lucene IDs
        Set<String> luceneIds = new HashSet<>();
        List<Document> allLucene = lexicalIndexService.listAll(lexicalGeneration, Integer.MAX_VALUE);
        for (Document doc : allLucene) {
            String chunkId = doc.metadata().getString("chunk_id");
            if (chunkId != null && !chunkId.isBlank()) {
                luceneIds.add(chunkId);
            }
        }

        // Count validation
        long manifestChunks = manifestIds.size();
        long qdrantChunks = qdrantIds.size();
        long lexicalChunks = luceneIds.size();
        if (manifestChunks != qdrantChunks || manifestChunks != lexicalChunks) {
            throw new IllegalStateException(
                    "Index count mismatch: manifest=" + manifestChunks
                            + ", qdrant=" + qdrantChunks
                            + ", lucene=" + lexicalChunks);
        }

        // ID set equality
        if (!manifestIds.equals(qdrantIds)) {
            Set<String> missingInQdrant = new HashSet<>(manifestIds);
            missingInQdrant.removeAll(qdrantIds);
            Set<String> extraInQdrant = new HashSet<>(qdrantIds);
            extraInQdrant.removeAll(manifestIds);
            throw new IllegalStateException(
                    "Qdrant ID mismatch: missing=" + missingInQdrant + ", extra=" + extraInQdrant);
        }
        if (!manifestIds.equals(luceneIds)) {
            Set<String> missingInLucene = new HashSet<>(manifestIds);
            missingInLucene.removeAll(luceneIds);
            Set<String> extraInLucene = new HashSet<>(luceneIds);
            extraInLucene.removeAll(manifestIds);
            throw new IllegalStateException(
                    "Lucene ID mismatch: missing=" + missingInLucene + ", extra=" + extraInLucene);
        }

        // Duplicate check (by set property already ensured)
        // Required seed document validation
        for (String required : REQUIRED_SEED_DOCUMENTS) {
            Path seed = Paths.get(appConfig.getQdrant().getDataPath(), required);
            String seedDocId = manifestManager.stableFileDocId(seed);
            ManifestRecord website = manifest.getDocuments().get(seedDocId);
            if (website == null || website.getChunkIds().isEmpty()) {
                throw new IllegalStateException(
                        "Required seed document " + required + " is missing or empty");
            }
        }

        // Verify collection dimension
        CollectionInfo info = client.getCollectionInfoAsync(qdrantCollection).get();
        long actualSize = info.getConfig()
                .getParams()
                .getVectorsConfig()
                .getParams()
                .getSize();
        int expectedSize = modelFactory.getEmbeddingModel().embed("dimension probe")
                .content().vector().length;
        if (actualSize != expectedSize) {
            throw new IllegalStateException(
                    "Collection dimension mismatch: expected=" + expectedSize + ", actual=" + actualSize);
        }

        // Source-type presence is logged but not enforced as a hard failure
        Set<String> presentSourceTypes = manifest.getDocuments().values().stream()
                .map(ManifestRecord::getSourceType)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        log.info("Rebuilt index source types present: {}", presentSourceTypes);
    }

    // ==================== CLEANUP ====================

    private void cleanupPreviousGeneration(
            QdrantClient client,
            String previousCollection,
            String previousLexicalGeneration,
            String activeCollection,
            String activeGeneration) {
        if (previousCollection != null
                && !previousCollection.equals(collectionName)
                && !previousCollection.equals(activeCollection)) {
            try {
                client.deleteCollectionAsync(previousCollection).get();
            } catch (Exception error) {
                log.warn("Failed to remove old Qdrant collection {}: {}",
                        previousCollection, error.getMessage());
            }
        }
        if (previousLexicalGeneration != null
                && !previousLexicalGeneration.equals(activeGeneration)) {
            lexicalIndexService.deleteGeneration(previousLexicalGeneration);
        }
    }

    // ==================== HEALTH ====================

    private Set<String> currentManifestChunkIds() {
        return manifestManager.loadManifest().getDocuments().values().stream()
                .map(ManifestRecord::getChunkIds)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
    }

    public Map<String, Object> getIndexHealth() {
        ManifestData manifest = manifestManager.loadManifest();
        long manifestChunks = manifest.getDocuments().values().stream()
                .mapToLong(record -> record.getChunkIds() == null
                        ? 0
                        : record.getChunkIds().size())
                .sum();
        String qdrantCollection = activeCollectionName();
        String lexicalGeneration = activeLexicalGeneration();
        long densePoints = -1;
        try {
            densePoints = getClient().countAsync(qdrantCollection).get();
        } catch (Exception error) {
            log.warn("Could not count Qdrant points: {}", error.getMessage());
        }
        long lexicalDocuments = lexicalIndexService.count(lexicalGeneration);
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("dense_points", densePoints);
        health.put("lexical_documents", lexicalDocuments);
        health.put("manifest_chunks", manifestChunks);
        health.put("qdrant_collection", qdrantCollection);
        health.put("lexical_generation", lexicalGeneration);
        health.put("index_generation", lexicalGeneration);
        health.put("index_consistent",
                densePoints >= 0
                        && densePoints == manifestChunks
                        && lexicalDocuments == manifestChunks);
        return health;
    }

    // ==================== UTILITIES ====================

    private List<Path> listEffectiveKnowledgeFiles() throws IOException {
        Path dataDir = Paths.get(appConfig.getQdrant().getDataPath());
        if (!Files.exists(dataDir)) {
            return Collections.emptyList();
        }
        List<String> allowedTypes = appConfig.getQdrant().getAllowKnowledgeFileTypes();
        try (var paths = Files.list(dataDir)) {
            List<Path> candidates = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> allowedTypes.contains(nameExtension(path)))
                    .sorted()
                    .collect(Collectors.toList());
            Set<String> transcriptStems = candidates.stream()
                    .filter(path -> "txt".equals(nameExtension(path)))
                    .map(this::pathStem)
                    .collect(Collectors.toSet());
            return candidates.stream()
                    .filter(path -> "txt".equals(nameExtension(path))
                            || !transcriptStems.contains(pathStem(path)))
                    .collect(Collectors.toList());
        }
    }

    private String pathStem(Path path) {
        String name = path.toAbsolutePath().normalize().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot).toLowerCase(Locale.ROOT) : name;
    }

    private Map<String, List<Document>> loadReusableBinaryChunks(
            ManifestData previousManifest,
            String previousCollection) {
        if (previousManifest.getDocuments().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, List<Document>> documents = new HashMap<>();
            for (Document document : scrollAllDocuments(previousCollection)) {
                String docId = document.metadata().getString("doc_id");
                if (docId != null) {
                    documents.computeIfAbsent(docId, ignored -> new ArrayList<>()).add(document);
                }
            }
            Map<String, List<Document>> reusable = new HashMap<>();
            for (Map.Entry<String, ManifestRecord> entry
                    : previousManifest.getDocuments().entrySet()) {
                ManifestRecord record = entry.getValue();
                if (record.getFilePath() == null
                        || "txt".equals(nameExtension(Paths.get(record.getFilePath())))) {
                    continue;
                }
                List<Document> chunks = documents.get(entry.getKey());
                if (chunks == null || chunks.size() != record.getChunkIds().size()) {
                    continue;
                }
                chunks.sort(Comparator.comparingInt(this::chunkIndex));
                reusable.put(entry.getKey(), chunks);
            }
            return reusable;
        } catch (Exception error) {
            log.warn("Could not reuse extracted binary chunks: {}", error.getMessage());
            return Collections.emptyMap();
        }
    }

    private List<Embedding> embedInBatches(List<TextSegment> segments) {
        List<Embedding> embeddings = new ArrayList<>(segments.size());
        for (int start = 0; start < segments.size(); start += 10) {
            List<TextSegment> batch = segments.subList(
                    start, Math.min(start + 10, segments.size()));
            embeddings.addAll(modelFactory.getEmbeddingModel().embedAll(batch).content());
        }
        return embeddings;
    }

    private Map<String, Value> buildPayload(
            Path path,
            Document chunk,
            String docId,
            String chunkId,
            int chunkIndex,
            String hash,
            String indexedAt,
            DocumentManifestManager.SourceClassification classification) {
        Map<String, Value> payload = new HashMap<>();
        payload.put("page_content", ValueFactory.value(chunk.text()));
        payload.put("doc_id", ValueFactory.value(docId));
        payload.put("chunk_id", ValueFactory.value(chunkId));
        payload.put("chunk_index", ValueFactory.value(chunkIndex));
        payload.put("source_type", ValueFactory.value(classification.getType()));
        payload.put("source_url", ValueFactory.value(""));
        payload.put("title", ValueFactory.value(
                path.getFileName().toString().replace("_", " ")));
        payload.put("filename", ValueFactory.value(path.getFileName().toString()));
        payload.put("file_path", ValueFactory.value(path.toAbsolutePath().toString()));
        payload.put("updated_at", ValueFactory.value(indexedAt));
        payload.put("hash", ValueFactory.value(hash));
        payload.put("permission_scope", ValueFactory.value("public"));
        payload.put("source_trust_label", ValueFactory.value(classification.getLabel()));
        payload.put("source_trust_note", ValueFactory.value(classification.getNote()));
        payload.put("source_priority", ValueFactory.value(classification.getPriority()));
        chunk.metadata().asMap().forEach((key, value) ->
                payload.put("loader_" + key, ValueFactory.value(value)));
        return payload;
    }

    private PointStruct pointFromPayload(
            String chunkId,
            Embedding embedding,
            Map<String, Value> payload) {
        List<Float> vector = new ArrayList<>(embedding.vector().length);
        for (float value : embedding.vector()) {
            vector.add(value);
        }
        return PointStruct.newBuilder()
                .setId(PointIdFactory.id(UUID.fromString(chunkId)))
                .setVectors(Vectors.newBuilder()
                        .setVector(io.qdrant.client.grpc.Points.Vector.newBuilder()
                                .addAllData(vector)
                                .build())
                        .build())
                .putAllPayload(payload)
                .build();
    }

    private void upsertBatches(
            QdrantClient client,
            String targetCollection,
            List<PointStruct> points) throws Exception {
        for (int start = 0; start < points.size(); start += 20) {
            client.upsertAsync(
                    targetCollection,
                    points.subList(start, Math.min(start + 20, points.size()))).get();
        }
    }

    private ManifestRecord buildManifestRecord(
            Path path,
            String docId,
            String hash,
            String indexedAt,
            DocumentManifestManager.SourceClassification classification,
            List<String> chunkIds) throws IOException {
        ManifestRecord record = new ManifestRecord();
        record.setDocId(docId);
        record.setSourceType(classification.getType());
        record.setTitle(path.getFileName().toString().replace("_", " "));
        record.setFilePath(path.toAbsolutePath().toString());
        record.setUpdatedAt(indexedAt);
        record.setHash(hash);
        record.setPermissionScope("public");
        record.setChunkIds(chunkIds);
        record.setIndexFingerprint(indexFingerprint);
        record.setIndexConfig(new LinkedHashMap<>(indexConfig));
        record.setIndexedAt(indexedAt);
        Map<String, Object> extra = new HashMap<>();
        extra.put("filename", path.getFileName().toString());
        extra.put("extension", nameExtension(path));
        extra.put("size_bytes", Files.size(path));
        extra.put("source_trust_label", classification.getLabel());
        extra.put("source_trust_note", classification.getNote());
        extra.put("source_priority", classification.getPriority());
        record.setExtra(extra);
        return record;
    }

    private Mono<List<Document>> loadFileContent(Path path) {
        String filename = path.getFileName().toString().toLowerCase();
        if (filename.endsWith(".txt")) {
            return Mono.just(fileExtractors.txtLoader(path));
        } else if (filename.endsWith(".pdf")) {
            return Mono.just(fileExtractors.pdfLoader(path));
        } else if (List.of("png", "jpg", "jpeg", "webp", "gif").stream().anyMatch(filename::endsWith)) {
            return fileExtractors.imageLoader(path);
        }
        return Mono.just(Collections.emptyList());
    }

    private List<Document> splitDocuments(List<Document> rawDocs) {
        List<Document> chunks = new ArrayList<>();
        PythonCompatibleTextSplitter splitter = new PythonCompatibleTextSplitter(
                appConfig.getQdrant().getChunkSize(),
                appConfig.getQdrant().getChunkOverlap()
        );
        for (Document doc : rawDocs) {
            chunks.addAll(splitter.split(doc));
        }
        return chunks;
    }

    public static String pythonCompatibleChunkId(String docId, int chunkIndex) {
        UUID namespaceDns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
        byte[] namespace = ByteBuffer.allocate(16)
                .putLong(namespaceDns.getMostSignificantBits())
                .putLong(namespaceDns.getLeastSignificantBits())
                .array();
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(namespace);
            byte[] hash = sha1.digest(
                    (docId + ":chunk:" + chunkIndex).getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer buffer = ByteBuffer.wrap(hash);
            return new UUID(buffer.getLong(), buffer.getLong()).toString();
        } catch (Exception error) {
            throw new IllegalStateException("Failed to create UUID v5", error);
        }
    }

    private String nameExtension(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }
}
