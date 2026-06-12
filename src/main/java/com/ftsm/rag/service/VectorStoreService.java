package com.ftsm.rag.service;

import com.ftsm.rag.config.AppConfig;
import com.ftsm.rag.model.ManifestData;
import com.ftsm.rag.model.ManifestRecord;
import com.ftsm.rag.store.DocumentManifestManager;
import com.ftsm.rag.utils.FileExtractors;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
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
import java.security.MessageDigest;
import java.util.*;
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

    private final AtomicReference<QdrantClient> clientRef = new AtomicReference<>();
    private final ReentrantLock indexMutationLock = new ReentrantLock();
    private final String collectionName;
    private final Map<String, Object> indexConfig;
    private final String indexFingerprint;

    public VectorStoreService(AppConfig appConfig, ModelFactory modelFactory, 
                              DocumentManifestManager manifestManager, FileExtractors fileExtractors,
                              LocalQdrantManager localQdrantManager) {
        this.appConfig = appConfig;
        this.modelFactory = modelFactory;
        this.manifestManager = manifestManager;
        this.fileExtractors = fileExtractors;
        this.localQdrantManager = localQdrantManager;
        this.collectionName = appConfig.getQdrant().getCollectionName();
        this.indexConfig = buildIndexConfig();
        this.indexFingerprint = buildIndexFingerprint(indexConfig);
    }

    private Map<String, Object> buildIndexConfig() {
        Map<String, Object> config = new TreeMap<>();
        config.put("schema_version", 3);
        config.put("embedding_model_name", appConfig.getDashscope().getEmbeddingModel());
        config.put("collection_name", collectionName);
        config.put("chunk_size", appConfig.getQdrant().getChunkSize());
        config.put("chunk_overlap", appConfig.getQdrant().getChunkOverlap());
        config.put("splitter", "recursive-separator-v1");
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
                    
                    try {
                        ensureCollectionExists(client);
                    } catch (Exception e) {
                        log.error("Failed to initialize Qdrant collection", e);
                    }
                }
            }
        }
        return client;
    }

    private void ensureCollectionExists(QdrantClient client) throws Exception {
        boolean exists = client.listCollectionsAsync().get().contains(collectionName);
        if (!exists) {
            log.info("Collection {} does not exist. Auto-creating...", collectionName);
            
            // Get sample embedding to determine dimensions
            int vectorSize = 1536; // Default fallback for text-embedding-v3
            try {
                Embedding embedding = modelFactory.getEmbeddingModel().embed("test").content();
                vectorSize = embedding.vector().length;
                log.info("Determined embedding vector size: {}", vectorSize);
            } catch (Exception e) {
                log.warn("Could not determine embedding size via API, using default size 1536: {}", e.getMessage());
            }

            client.createCollectionAsync(collectionName,
                    io.qdrant.client.grpc.Collections.VectorParams.newBuilder()
                            .setSize(vectorSize)
                            .setDistance(io.qdrant.client.grpc.Collections.Distance.Cosine)
                            .build()
            ).get();
            log.info("Collection {} created successfully", collectionName);
        }
    }

    public List<Document> search(String query, int k) {
        Set<String> currentChunkIds = currentManifestChunkIds();
        if (currentChunkIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Document> dense = denseSearch(query, Math.max(k, appConfig.getQdrant().getHybridSearchLimit())).stream()
                .filter(document -> currentChunkIds.contains(document.metadata().getString("chunk_id")))
                .collect(Collectors.toList());
        List<Document> lexical = lexicalSearch(query, Math.max(k, appConfig.getQdrant().getHybridSearchLimit())).stream()
                .filter(document -> currentChunkIds.contains(document.metadata().getString("chunk_id")))
                .collect(Collectors.toList());
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

    private List<Document> denseSearch(String query, int limit) {
        try {
            QdrantClient client = getClient();
            Embedding embedding = modelFactory.getEmbeddingModel().embed(query).content();
            List<Float> queryVector = new ArrayList<>();
            for (float value : embedding.vector()) {
                queryVector.add(value);
            }
            SearchPoints searchRequest = SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
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

    private List<Document> lexicalSearch(String query, int limit) {
        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<Document> documents = scrollAllDocuments();
            if (documents.isEmpty()) {
                return documents;
            }

            List<List<String>> tokenized = documents.stream()
                    .map(document -> tokenize(document.text()))
                    .collect(Collectors.toList());
            double averageLength = tokenized.stream().mapToInt(List::size).average().orElse(1.0);
            Map<String, Integer> documentFrequency = new HashMap<>();
            for (List<String> terms : tokenized) {
                for (String term : new HashSet<>(terms)) {
                    if (queryTerms.contains(term)) {
                        documentFrequency.merge(term, 1, Integer::sum);
                    }
                }
            }

            Map<Document, Double> scores = new IdentityHashMap<>();
            String normalizedQuery = normalizeText(query);
            for (int i = 0; i < documents.size(); i++) {
                Document document = documents.get(i);
                List<String> terms = tokenized.get(i);
                Map<String, Long> termFrequency = terms.stream()
                        .collect(Collectors.groupingBy(term -> term, Collectors.counting()));
                double score = 0.0;
                for (String term : queryTerms) {
                    long tf = termFrequency.getOrDefault(term, 0L);
                    if (tf == 0) {
                        continue;
                    }
                    int df = documentFrequency.getOrDefault(term, 0);
                    double idf = Math.log(1.0 + (documents.size() - df + 0.5) / (df + 0.5));
                    double denominator = tf + 1.2 * (0.25 + 0.75 * terms.size() / averageLength);
                    score += idf * (tf * 2.2) / denominator;
                }
                if (normalizedQuery.length() >= 4
                        && normalizeText(document.text()).contains(normalizedQuery)) {
                    score += 3.0;
                }
                if (score > 0.0) {
                    scores.put(document, score);
                }
            }

            return scores.entrySet().stream()
                    .sorted(Map.Entry.<Document, Double>comparingByValue().reversed())
                    .limit(limit)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Lexical search failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Document> scrollAllDocuments() throws Exception {
        QdrantClient client = getClient();
        List<Document> documents = new ArrayList<>();
        PointId offset = null;
        do {
            ScrollPoints.Builder request = ScrollPoints.newBuilder()
                    .setCollectionName(collectionName)
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

    public List<Map<String, Object>> getDocumentChunks(Path path, int requestedLimit) {
        String docId = manifestManager.stableFileDocId(path);
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        ManifestRecord record = manifestManager.loadManifest().getDocuments().get(docId);
        if (record == null || record.getChunkIds() == null) {
            return Collections.emptyList();
        }
        Set<String> currentChunkIds = new HashSet<>(record.getChunkIds());
        try {
            return scrollAllDocuments().stream()
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

    public boolean deleteDocumentByPath(String filePathStr) {
        indexMutationLock.lock();
        try {
            return deleteDocumentByPathLocked(filePathStr);
        } finally {
            indexMutationLock.unlock();
        }
    }

    private boolean deleteDocumentByPathLocked(String filePathStr) {
        Path path = Paths.get(filePathStr);
        String docId = manifestManager.stableFileDocId(path);
        
        ManifestData manifest = manifestManager.loadManifest();
        ManifestRecord record = manifest.getDocuments().get(docId);
        if (record == null) {
            log.info("[knowledge delete] No manifest record found for {}", docId);
            return false;
        }

        manifest.getDocuments().remove(docId);
        manifestManager.updateManifestIndexState(manifest, null, true, null);
        manifestManager.saveManifest(manifest);

        List<String> chunkIds = record.getChunkIds();
        if (deleteChunks(chunkIds)) {
            log.info("[knowledge delete] Removed {} from manifest", docId);
            return true;
        }

        manifest.getDocuments().put(docId, record);
        manifestManager.updateManifestIndexState(manifest, "Vector index cleanup failed for " + docId, true, null);
        try {
            manifestManager.saveManifest(manifest);
        } catch (RuntimeException restoreError) {
            throw new IllegalStateException(
                    "Vector cleanup failed and the document manifest could not be restored",
                    restoreError
            );
        }
        return false;
    }

    private boolean deleteChunks(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return true;
        }
        QdrantClient client = getClient();
        List<PointId> pointIds = chunkIds.stream()
                .map(id -> PointIdFactory.id(UUID.fromString(id)))
                .collect(Collectors.toList());

        try {
            client.deleteAsync(collectionName, pointIds).get();
            log.info("[knowledge load] Deleted {} chunks from Qdrant", chunkIds.size());
            return true;
        } catch (Exception e) {
            log.error("[knowledge load] Failed to delete chunks from Qdrant", e);
            return false;
        }
    }

    public Map<String, Object> loadDocumentsIncremental(List<Path> targetPaths) {
        indexMutationLock.lock();
        try {
            return loadDocumentsIncrementalLocked(targetPaths);
        } finally {
            indexMutationLock.unlock();
        }
    }

    private Map<String, Object> loadDocumentsIncrementalLocked(List<Path> targetPaths) {
        ManifestData manifest = manifestManager.loadManifest();
        List<Path> allowedFiles;
        boolean modified = false;

        List<String> allowedTypes = appConfig.getQdrant().getAllowKnowledgeFileTypes();

        if (ensureCollectionCompatibleForIndexing(manifest, targetPaths == null)) {
            modified = true;
        }
        
        if (targetPaths == null) {
            // Scan directories
            Path dataDir = Paths.get(appConfig.getQdrant().getDataPath());
            allowedFiles = new ArrayList<>();
            try {
                if (Files.exists(dataDir)) {
                    try (var paths = Files.walk(dataDir)) {
                        paths.filter(Files::isRegularFile).forEach(p -> {
                        String ext = p.getFileName().toString();
                        int dot = ext.lastIndexOf('.');
                        if (dot > 0) {
                            String suffix = ext.substring(dot + 1).toLowerCase();
                            if (allowedTypes.contains(suffix)) {
                                allowedFiles.add(p);
                            }
                        }
                        });
                    }
                }
            } catch (IOException e) {
                log.error("Failed to list data directory", e);
            }

            // Sync deleted files
            Set<String> currentDocIds = allowedFiles.stream()
                    .map(manifestManager::stableFileDocId)
                    .collect(Collectors.toSet());

            List<String> toRemove = new ArrayList<>();
            for (String docId : manifest.getDocuments().keySet()) {
                if (docId.startsWith("file:") && !currentDocIds.contains(docId)) {
                    toRemove.add(docId);
                }
            }

            for (String docId : toRemove) {
                ManifestRecord r = manifest.getDocuments().get(docId);
                manifest.getDocuments().remove(docId);
                manifestManager.saveManifest(manifest);
                if (!deleteChunks(r.getChunkIds())) {
                    manifest.getDocuments().put(docId, r);
                    manifestManager.saveManifest(manifest);
                    continue;
                }
                modified = true;
                log.info("[knowledge load] Cleaned up missing document {}", docId);
            }
        } else {
            allowedFiles = targetPaths.stream()
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        int dot = name.lastIndexOf('.');
                        if (dot > 0) {
                            return allowedTypes.contains(name.substring(dot + 1).toLowerCase());
                        }
                        return false;
                    })
                    .collect(Collectors.toList());
        }

        List<String> errors = new ArrayList<>();

        for (Path p : allowedFiles) {
            List<String> addedChunkIds = new ArrayList<>();
            boolean manifestReferencesNewChunks = false;
            String currentDocId = null;
            ManifestRecord previous = null;
            try {
                String docId = manifestManager.stableFileDocId(p);
                currentDocId = docId;
                String hash = FileExtractors.getFileSha256Hex(p);
                DocumentManifestManager.SourceClassification classification = manifestManager.classifySource(p);
                
                previous = manifest.getDocuments().get(docId);
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

                // Index chunks
                List<String> chunkIds = new ArrayList<>();
                List<PointStruct> points = new ArrayList<>();
                
                // Collect texts for batch embedding and limit batch size to 10 matching DashScope API limit
                List<dev.langchain4j.data.segment.TextSegment> segments = chunkDocs.stream()
                        .map(chunk -> dev.langchain4j.data.segment.TextSegment.from(chunk.text()))
                        .collect(Collectors.toList());
                
                log.info("[knowledge load] Generating embeddings for {} chunks of {}", segments.size(), p.getFileName());
                List<Embedding> embeddings = new ArrayList<>();
                int embedBatchSize = 10;
                for (int j = 0; j < segments.size(); j += embedBatchSize) {
                    List<dev.langchain4j.data.segment.TextSegment> subBatch = segments.subList(j, Math.min(j + embedBatchSize, segments.size()));
                    List<Embedding> subEmbeddings = modelFactory.getEmbeddingModel().embedAll(subBatch).content();
                    embeddings.addAll(subEmbeddings);
                }
                
                for (int i = 0; i < chunkDocs.size(); i++) {
                    Document chunk = chunkDocs.get(i);
                    String rawIdStr = docId + ":" + indexFingerprint + ":" + hash + ":chunk:" + i;
                    String chunkId = UUID.nameUUIDFromBytes(rawIdStr.getBytes(StandardCharsets.UTF_8)).toString();
                    chunkIds.add(chunkId);

                    Embedding embedding = embeddings.get(i);
                    List<Float> vector = new ArrayList<>();
                    for (float f : embedding.vector()) {
                        vector.add(f);
                    }

                    // Build payload
                    Map<String, Value> payload = new HashMap<>();
                    payload.put("page_content", ValueFactory.value(chunk.text()));
                    payload.put("doc_id", ValueFactory.value(docId));
                    payload.put("chunk_id", ValueFactory.value(chunkId));
                    payload.put("chunk_index", ValueFactory.value(i));
                    payload.put("source_type", ValueFactory.value(classification.getType()));
                    payload.put("source_url", ValueFactory.value(recordValue(classification.getType())));
                    payload.put("title", ValueFactory.value(p.getFileName().toString().replace("_", " ")));
                    payload.put("file_path", ValueFactory.value(p.toAbsolutePath().toString()));
                    payload.put("updated_at", ValueFactory.value(manifestManager.getUtcIsoNow()));
                    payload.put("hash", ValueFactory.value(hash));
                    payload.put("permission_scope", ValueFactory.value("public"));
                    payload.put("source_trust_label", ValueFactory.value(classification.getLabel()));
                    payload.put("source_trust_note", ValueFactory.value(classification.getNote()));
                    payload.put("source_priority", ValueFactory.value(classification.getPriority()));

                    // Copy chunk metadata
                    chunk.metadata().asMap().forEach((key, val) -> {
                        payload.put("loader_" + key, ValueFactory.value(val));
                    });

                    points.add(PointStruct.newBuilder()
                            .setId(PointIdFactory.id(UUID.fromString(chunkId)))
                            .setVectors(Vectors.newBuilder()
                                    .setVector(io.qdrant.client.grpc.Points.Vector.newBuilder().addAllData(vector).build())
                                    .build())
                            .putAllPayload(payload)
                            .build());
                }

                // Batch upsert to Qdrant
                int batchSize = 20;
                QdrantClient client = getClient();
                for (int i = 0; i < points.size(); i += batchSize) {
                    List<PointStruct> batch = points.subList(i, Math.min(i + batchSize, points.size()));
                    client.upsertAsync(collectionName, batch).get();
                    for (PointStruct point : batch) {
                        addedChunkIds.add(point.getId().getUuid());
                    }
                }

                // Commit the new manifest record before removing the previous vectors. This keeps
                // the durable manifest from ever pointing at chunks that were already deleted.
                ManifestRecord record = new ManifestRecord();
                record.setDocId(docId);
                record.setSourceType(classification.getType());
                record.setTitle(p.getFileName().toString().replace("_", " "));
                record.setFilePath(p.toAbsolutePath().toString());
                record.setUpdatedAt(manifestManager.getUtcIsoNow());
                record.setHash(hash);
                record.setPermissionScope("public");
                record.setChunkIds(chunkIds);
                record.setIndexFingerprint(indexFingerprint);
                record.setIndexConfig(new LinkedHashMap<>(indexConfig));
                record.setIndexedAt(manifestManager.getUtcIsoNow());
                
                Map<String, Object> extra = new HashMap<>();
                extra.put("filename", p.getFileName().toString());
                extra.put("extension", nameExtension(p));
                extra.put("size_bytes", Files.size(p));
                extra.put("source_trust_label", classification.getLabel());
                extra.put("source_trust_note", classification.getNote());
                extra.put("source_priority", classification.getPriority());
                record.setExtra(extra);

                manifest.getDocuments().put(docId, record);
                manifestManager.saveManifest(manifest);
                manifestReferencesNewChunks = true;

                if (previous != null && !deleteChunks(previous.getChunkIds())) {
                    manifest.getDocuments().put(docId, previous);
                    try {
                        manifestManager.saveManifest(manifest);
                        manifestReferencesNewChunks = false;
                        deleteChunks(addedChunkIds);
                    } catch (RuntimeException restoreError) {
                        manifest.getDocuments().put(docId, record);
                        throw new IllegalStateException(
                                "Failed to remove the previous index; the new manifest was retained",
                                restoreError
                        );
                    }
                    throw new IllegalStateException("Failed to remove the previous document index");
                }

                modified = true;
                log.info("[knowledge load] Indexed {} chunks from {}", chunkIds.size(), p.getFileName());

            } catch (Exception e) {
                if (!manifestReferencesNewChunks && !addedChunkIds.isEmpty()) {
                    deleteChunks(addedChunkIds);
                }
                if (!manifestReferencesNewChunks && currentDocId != null) {
                    if (previous == null) {
                        manifest.getDocuments().remove(currentDocId);
                    } else {
                        manifest.getDocuments().put(currentDocId, previous);
                    }
                }
                errors.add(p.getFileName() + ": " + e.getMessage());
                log.error("Failed to load document {}", p, e);
            }
        }

        String errSummary = errors.isEmpty() ? null : String.join("; ", errors);
        manifestManager.updateManifestIndexState(manifest, errSummary, modified, indexFingerprint);
        manifestManager.saveManifest(manifest);

        Map<String, Object> result = new HashMap<>();
        result.put("success", errors.isEmpty());
        result.put("errors", errors);
        result.put("error_summary", errSummary);
        result.put("modified", modified);
        return result;
    }

    private Set<String> currentManifestChunkIds() {
        return manifestManager.loadManifest().getDocuments().values().stream()
                .map(ManifestRecord::getChunkIds)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
    }

    private boolean ensureCollectionCompatibleForIndexing(ManifestData manifest, boolean fullReindex) {
        try {
            QdrantClient client = getClient();
            int expectedSize = modelFactory.getEmbeddingModel().embed("dimension probe").content().vector().length;
            CollectionInfo info = client.getCollectionInfoAsync(collectionName).get();
            long actualSize = info.getConfig()
                    .getParams()
                    .getVectorsConfig()
                    .getParams()
                    .getSize();
            if (actualSize == expectedSize) {
                return false;
            }
            if (!fullReindex) {
                throw new IllegalStateException(
                        "Embedding dimension changed from " + actualSize + " to " + expectedSize
                                + "; a full reindex is required"
                );
            }

            log.warn("Recreating Qdrant collection because vector size changed from {} to {}",
                    actualSize, expectedSize);
            client.recreateCollectionAsync(
                    collectionName,
                    io.qdrant.client.grpc.Collections.VectorParams.newBuilder()
                            .setSize(expectedSize)
                            .setDistance(io.qdrant.client.grpc.Collections.Distance.Cosine)
                            .build()
            ).get();
            manifest.getDocuments().clear();
            manifestManager.updateManifestIndexState(manifest, null, true, indexFingerprint);
            manifestManager.saveManifest(manifest);
            return true;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to validate Qdrant collection compatibility", e);
        }
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
        DocumentSplitter splitter = DocumentSplitters.recursive(
                appConfig.getQdrant().getChunkSize(),
                appConfig.getQdrant().getChunkOverlap()
        );
        for (Document doc : rawDocs) {
            if (doc.text() == null || doc.text().trim().isEmpty()) {
                continue;
            }
            for (TextSegment segment : splitter.split(doc)) {
                chunks.add(Document.from(segment.text(), segment.metadata()));
            }
        }
        return chunks;
    }

    private String recordValue(String val) {
        return val != null ? val : "";
    }

    private String nameExtension(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }
}
