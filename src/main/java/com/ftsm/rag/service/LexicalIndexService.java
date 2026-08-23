package com.ftsm.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ftsm.rag.config.AppConfig;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.QueryBuilder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Slf4j
@Service
public class LexicalIndexService {

    private static final String CONTENT = "content";
    private static final String CHUNK_ID = "chunk_id";
    private static final String DOC_ID = "doc_id";
    private static final String METADATA = "metadata";

    private final Path basePath;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public LexicalIndexService(AppConfig appConfig) {
        this.basePath = Paths.get(appConfig.getQdrant().getLexicalIndexPath())
                .toAbsolutePath()
                .normalize();
    }

    public Path generationPath(String generation) {
        String safeGeneration = requireSafeGeneration(generation);
        return basePath.resolve(safeGeneration).normalize();
    }

    public void rebuild(String generation, List<Document> documents) {
        lock.writeLock().lock();
        try {
            Path path = generationPath(generation);
            deleteDirectory(path);
            Files.createDirectories(path);
            try (Directory directory = FSDirectory.open(path);
                 Analyzer analyzer = new CJKAnalyzer();
                 IndexWriter writer = new IndexWriter(
                         directory,
                         writerConfig(analyzer, IndexWriterConfig.OpenMode.CREATE))) {
                for (Document document : documents) {
                    writer.addDocument(toLuceneDocument(document));
                }
                writer.commit();
            }
        } catch (IOException error) {
            throw new IllegalStateException("Failed to rebuild Lucene index", error);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void replaceDocument(String generation, String docId, List<Document> documents) {
        if (generation == null || generation.isBlank()) {
            if (documents == null || documents.isEmpty()) {
                return;
            }
            throw new IllegalStateException("Lucene generation is not initialized");
        }
        lock.writeLock().lock();
        try {
            Path path = generationPath(generation);
            Files.createDirectories(path);
            try (Directory directory = FSDirectory.open(path);
                 Analyzer analyzer = new CJKAnalyzer();
                 IndexWriter writer = new IndexWriter(
                         directory,
                         writerConfig(analyzer, IndexWriterConfig.OpenMode.CREATE_OR_APPEND))) {
                writer.deleteDocuments(new Term(DOC_ID, docId));
                for (Document document : documents) {
                    writer.addDocument(toLuceneDocument(document));
                }
                writer.commit();
            }
        } catch (IOException error) {
            throw new IllegalStateException("Failed to update Lucene document " + docId, error);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void deleteDocument(String generation, String docId) {
        if (generation == null || generation.isBlank()) {
            return;
        }
        replaceDocument(generation, docId, Collections.emptyList());
    }

    public List<Document> search(String generation, String queryText, int limit) {
        if (generation == null || generation.isBlank() || queryText == null || queryText.isBlank()) {
            return Collections.emptyList();
        }
        lock.readLock().lock();
        try {
            Path path = generationPath(generation);
            if (!Files.isDirectory(path)) {
                return Collections.emptyList();
            }
            try (Directory directory = FSDirectory.open(path);
                 DirectoryReader reader = DirectoryReader.open(directory);
                 Analyzer analyzer = new CJKAnalyzer()) {
                Query query = new QueryBuilder(analyzer).createBooleanQuery(CONTENT, queryText);
                if (query == null) {
                    return Collections.emptyList();
                }
                IndexSearcher searcher = new IndexSearcher(reader);
                searcher.setSimilarity(new BM25Similarity());
                TopDocs results = searcher.search(query, Math.max(1, limit));
                List<Document> documents = new ArrayList<>(results.scoreDocs.length);
                for (ScoreDoc scoreDoc : results.scoreDocs) {
                    documents.add(fromLuceneDocument(searcher.storedFields().document(scoreDoc.doc)));
                }
                return documents;
            }
        } catch (IOException error) {
            log.warn("Lucene search failed for generation {}: {}", generation, error.getMessage());
            return Collections.emptyList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Document> findByDocumentId(String generation, String docId, int limit) {
        if (generation == null || generation.isBlank()) {
            return Collections.emptyList();
        }
        lock.readLock().lock();
        try {
            Path path = generationPath(generation);
            if (!Files.isDirectory(path)) {
                return Collections.emptyList();
            }
            try (Directory directory = FSDirectory.open(path)) {
                if (!DirectoryReader.indexExists(directory)) {
                    return Collections.emptyList();
                }
                try (DirectoryReader reader = DirectoryReader.open(directory)) {
                    IndexSearcher searcher = new IndexSearcher(reader);
                    TopDocs results = searcher.search(
                            new org.apache.lucene.search.TermQuery(new Term(DOC_ID, docId)),
                            Math.max(1, limit));
                    List<Document> documents = new ArrayList<>(results.scoreDocs.length);
                    for (ScoreDoc scoreDoc : results.scoreDocs) {
                        documents.add(fromLuceneDocument(searcher.storedFields().document(scoreDoc.doc)));
                    }
                    return documents;
                }
            }
        } catch (IOException error) {
            log.warn("Failed to read Lucene document {}: {}", docId, error.getMessage());
            return Collections.emptyList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Document> listAll(String generation, int limit) {
        if (generation == null || generation.isBlank()) {
            return Collections.emptyList();
        }
        lock.readLock().lock();
        try {
            Path path = generationPath(generation);
            if (!Files.isDirectory(path)) {
                return Collections.emptyList();
            }
            try (Directory directory = FSDirectory.open(path)) {
                if (!DirectoryReader.indexExists(directory)) {
                    return Collections.emptyList();
                }
                try (DirectoryReader reader = DirectoryReader.open(directory)) {
                    IndexSearcher searcher = new IndexSearcher(reader);
                    TopDocs results = searcher.search(
                            new org.apache.lucene.search.MatchAllDocsQuery(),
                            Math.max(1, limit));
                    List<Document> documents = new ArrayList<>(results.scoreDocs.length);
                    for (ScoreDoc scoreDoc : results.scoreDocs) {
                        documents.add(fromLuceneDocument(searcher.storedFields().document(scoreDoc.doc)));
                    }
                    return documents;
                }
            }
        } catch (IOException error) {
            log.warn("Failed to list all Lucene documents for generation {}: {}", generation, error.getMessage());
            return Collections.emptyList();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long count(String generation) {
        if (generation == null || generation.isBlank()) {
            return 0;
        }
        lock.readLock().lock();
        try {
            Path path = generationPath(generation);
            if (!Files.isDirectory(path)) {
                return 0;
            }
            try (Directory directory = FSDirectory.open(path)) {
                if (!DirectoryReader.indexExists(directory)) {
                    return 0;
                }
                try (DirectoryReader reader = DirectoryReader.open(directory)) {
                    return reader.numDocs();
                }
            }
        } catch (IOException error) {
            return 0;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void deleteGeneration(String generation) {
        if (generation == null || generation.isBlank()) {
            return;
        }
        lock.writeLock().lock();
        try {
            deleteDirectory(generationPath(generation));
        } catch (IOException error) {
            log.warn("Failed to delete Lucene generation {}: {}", generation, error.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    private IndexWriterConfig writerConfig(Analyzer analyzer, IndexWriterConfig.OpenMode mode) {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(mode);
        config.setSimilarity(new BM25Similarity());
        return config;
    }

    private org.apache.lucene.document.Document toLuceneDocument(Document source) throws IOException {
        Map<String, String> metadata = source.metadata().asMap();
        String chunkId = metadata.getOrDefault(CHUNK_ID, "");
        String docId = metadata.getOrDefault(DOC_ID, "");
        if (chunkId.isBlank() || docId.isBlank()) {
            throw new IllegalArgumentException("Lucene documents require chunk_id and doc_id metadata");
        }

        org.apache.lucene.document.Document target = new org.apache.lucene.document.Document();
        target.add(new StringField(CHUNK_ID, chunkId, Field.Store.YES));
        target.add(new StringField(DOC_ID, docId, Field.Store.YES));
        target.add(new TextField(CONTENT, source.text(), Field.Store.YES));
        target.add(new StoredField(METADATA, objectMapper.writeValueAsString(metadata)));
        return target;
    }

    private Document fromLuceneDocument(org.apache.lucene.document.Document source) throws IOException {
        Map<String, String> values = objectMapper.readValue(
                source.get(METADATA),
                new TypeReference<Map<String, String>>() {});
        Metadata metadata = new Metadata(values);
        return Document.from(source.get(CONTENT), metadata);
    }

    private String requireSafeGeneration(String generation) {
        if (generation == null || !generation.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid Lucene generation");
        }
        return generation;
    }

    private void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            List<Path> entries = paths.sorted((left, right) -> right.compareTo(left)).toList();
            for (Path entry : entries) {
                Files.deleteIfExists(entry);
            }
        }
    }
}
