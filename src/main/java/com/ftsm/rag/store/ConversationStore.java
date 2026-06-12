package com.ftsm.rag.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ftsm.rag.config.AppConfig;
import com.ftsm.rag.model.ConversationDetails;
import com.ftsm.rag.model.ConversationIndexItem;
import com.ftsm.rag.model.ConversationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ConversationStore {

    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;
    private final Path conversationsDir;
    private final Path indexFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private static final Pattern SAFE_ID_RE = Pattern.compile("^[0-9a-fA-F-]{1,64}$");

    public ConversationStore(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        
        Path dataDir = Paths.get(appConfig.getQdrant().getDataPath());
        this.conversationsDir = dataDir.resolve("conversations");
        this.indexFile = conversationsDir.resolve("index.json");
        
        try {
            Files.createDirectories(conversationsDir);
        } catch (IOException e) {
            log.error("Failed to create conversations directory", e);
        }

        migrateLegacy(dataDir);
    }

    private void atomicWrite(Path path, String content) throws IOException {
        Path tempFile = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tempFile, content, StandardCharsets.UTF_8);
        try {
            Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveError) {
            Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private Path getConvPath(String conversationId) {
        if (conversationId == null || !SAFE_ID_RE.matcher(conversationId).matches()) {
            return null;
        }
        return conversationsDir.resolve(conversationId + ".json");
    }

    public List<ConversationIndexItem> listItems(Integer limit) {
        lock.readLock().lock();
        try {
            List<ConversationIndexItem> items = loadIndex();
            items.sort((a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt()));
            if (limit != null && limit > 0 && limit < items.size()) {
                return items.subList(0, limit);
            }
            return items;
        } finally {
            lock.readLock().unlock();
        }
    }

    public ConversationDetails get(String conversationId) {
        requireValidConversationId(conversationId);
        Path p = getConvPath(conversationId);
        if (!Files.exists(p)) {
            return null;
        }
        lock.readLock().lock();
        try {
            return objectMapper.readValue(p.toFile(), ConversationDetails.class);
        } catch (IOException e) {
            log.error("Failed to read conversation details for {}", conversationId, e);
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<ConversationMessage> recentMessages(String conversationId, int maxTurns) {
        ConversationDetails conv = get(conversationId);
        if (conv == null || conv.getMessages() == null) {
            return Collections.emptyList();
        }
        int limit = maxTurns * 2;
        List<ConversationMessage> msgs = conv.getMessages();
        if (msgs.size() > limit) {
            return msgs.subList(msgs.size() - limit, msgs.size());
        }
        return msgs;
    }

    public ConversationDetails create(String conversationId) {
        requireValidConversationId(conversationId);
        ConversationDetails conv = new ConversationDetails();
        conv.setId(conversationId);
        conv.setTitle("New chat");
        conv.setUpdatedAt(Instant.now().getEpochSecond());
        conv.setMessages(new ArrayList<>());
        
        lock.writeLock().lock();
        try {
            saveConv(conv);
        } finally {
            lock.writeLock().unlock();
        }
        return conv;
    }

    public boolean delete(String conversationId) {
        requireValidConversationId(conversationId);
        lock.writeLock().lock();
        try {
            Path path = getConvPath(conversationId);
            boolean found = path != null && Files.exists(path);
            if (!found) {
                return false;
            }

            List<ConversationIndexItem> index = loadIndex();
            List<ConversationIndexItem> newIndex = index.stream()
                    .filter(i -> !i.getId().equals(conversationId))
                    .collect(Collectors.toList());

            String previousContent;
            try {
                previousContent = Files.readString(path, StandardCharsets.UTF_8);
                Files.delete(path);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to delete conversation " + conversationId, e);
            }

            try {
                saveIndex(newIndex);
            } catch (RuntimeException indexError) {
                try {
                    atomicWrite(path, previousContent);
                } catch (IOException restoreError) {
                    indexError.addSuppressed(restoreError);
                }
                throw indexError;
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void deleteAll() {
        lock.writeLock().lock();
        try {
            List<ConversationIndexItem> index = loadIndex();
            Path stagingDir = conversationsDir.resolve(".deleting-" + UUID.randomUUID());
            Map<Path, Path> stagedFiles = new LinkedHashMap<>();
            try {
                Files.createDirectories(stagingDir);
                for (ConversationIndexItem item : index) {
                    Path source = getConvPath(item.getId());
                    if (source == null || !Files.exists(source)) {
                        continue;
                    }
                    Path staged = stagingDir.resolve(source.getFileName());
                    moveFile(source, staged);
                    stagedFiles.put(source, staged);
                }
                saveIndex(new ArrayList<>());
            } catch (Exception error) {
                rollbackStagedFiles(stagedFiles, error);
                throw error instanceof RuntimeException
                        ? (RuntimeException) error
                        : new IllegalStateException("Failed to delete conversations", error);
            }

            for (Path staged : stagedFiles.values()) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException e) {
                    log.warn("Failed to remove staged conversation file {}: {}", staged, e.getMessage());
                }
            }
            try {
                Files.deleteIfExists(stagingDir);
            } catch (IOException e) {
                log.warn("Failed to remove conversation staging directory {}: {}", stagingDir, e.getMessage());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void appendTurn(String conversationId, String userContent, String assistantContent, String title) {
        requireValidConversationId(conversationId);
        long now = Instant.now().getEpochSecond();
        lock.writeLock().lock();
        try {
            ConversationDetails conv = get(conversationId);
            if (conv == null) {
                conv = new ConversationDetails();
                conv.setId(conversationId);
                conv.setTitle(title != null ? title : "New chat");
                conv.setUpdatedAt(now);
                conv.setMessages(new ArrayList<>());
            }
            List<ConversationMessage> msgs = conv.getMessages();
            msgs.add(new ConversationMessage("user", userContent, now));
            msgs.add(new ConversationMessage("assistant", assistantContent, now));

            int maxMessages = appConfig.getConversation().getMaxMessages();
            if (msgs.size() > maxMessages) {
                conv.setMessages(new ArrayList<>(msgs.subList(msgs.size() - maxMessages, msgs.size())));
            }

            if (title != null) {
                conv.setTitle(title);
            } else if (conv.getTitle() == null || conv.getTitle().equals("New chat")) {
                conv.setTitle("New chat");
            }
            
            conv.setUpdatedAt(now);
            saveConv(conv);
        } finally {
            lock.writeLock().unlock();
        }
    }

    // --- Private Helper Methods ---

    private List<ConversationIndexItem> loadIndex() {
        if (!Files.exists(indexFile)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(indexFile.toFile(), new TypeReference<List<ConversationIndexItem>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read conversation index", e);
        }
    }

    private void saveIndex(List<ConversationIndexItem> items) {
        try {
            String content = objectMapper.writeValueAsString(items);
            atomicWrite(indexFile, content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save conversation index", e);
        }
    }

    private void saveConv(ConversationDetails conv) {
        String cid = conv.getId();
        requireValidConversationId(cid);
        Path path = getConvPath(cid);
        String previousContent = null;
        boolean existed = Files.exists(path);
        try {
            if (existed) {
                previousContent = Files.readString(path, StandardCharsets.UTF_8);
            }
            String content = objectMapper.writeValueAsString(conv);
            atomicWrite(path, content);

            List<ConversationIndexItem> index = loadIndex();
            // Remove previous entry if any
            index.removeIf(i -> i.getId().equals(cid));
            
            ConversationIndexItem item = new ConversationIndexItem();
            item.setId(cid);
            item.setTitle(conv.getTitle());
            item.setUpdatedAt(conv.getUpdatedAt());
            index.add(item);
            
            List<ConversationIndexItem> pruned = prune(index);
            saveIndex(index);
            deletePrunedFiles(pruned);
        } catch (IOException e) {
            restoreConversationFile(path, existed, previousContent, e);
            throw new IllegalStateException("Failed to save conversation " + cid, e);
        } catch (RuntimeException e) {
            restoreConversationFile(path, existed, previousContent, e);
            throw e;
        }
    }

    private void requireValidConversationId(String conversationId) {
        if (getConvPath(conversationId) == null) {
            throw new IllegalArgumentException("Invalid conversation id");
        }
    }

    private void restoreConversationFile(Path path, boolean existed, String previousContent, Exception originalError) {
        try {
            if (existed && previousContent != null) {
                atomicWrite(path, previousContent);
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException restoreError) {
            originalError.addSuppressed(restoreError);
        }
    }

    private void moveFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveError) {
            Files.move(source, target);
        }
    }

    private void rollbackStagedFiles(Map<Path, Path> stagedFiles, Exception originalError) {
        List<Map.Entry<Path, Path>> entries = new ArrayList<>(stagedFiles.entrySet());
        Collections.reverse(entries);
        for (Map.Entry<Path, Path> entry : entries) {
            if (!Files.exists(entry.getValue())) {
                continue;
            }
            try {
                moveFile(entry.getValue(), entry.getKey());
            } catch (IOException restoreError) {
                originalError.addSuppressed(restoreError);
            }
        }
    }

    private List<ConversationIndexItem> prune(List<ConversationIndexItem> index) {
        int maxConvs = appConfig.getConversation().getMaxConversations();
        if (index.size() <= maxConvs) {
            return Collections.emptyList();
        }
        index.sort((a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt()));
        List<ConversationIndexItem> toDelete = new ArrayList<>(index.subList(maxConvs, index.size()));
        index.subList(maxConvs, index.size()).clear();
        return toDelete;
    }

    private void deletePrunedFiles(List<ConversationIndexItem> toDelete) {
        for (ConversationIndexItem item : toDelete) {
            Path p = getConvPath(item.getId());
            if (p != null && Files.exists(p)) {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    log.warn("Failed to delete pruned conversation {}: {}", item.getId(), e.getMessage());
                }
            }
        }
    }

    private void migrateLegacy(Path baseDir) {
        Path legacyFile = baseDir.resolve("conversations.json");
        if (!Files.exists(legacyFile) || Files.exists(indexFile)) {
            return;
        }
        log.info("Migrating legacy conversations from conversations.json");
        try {
            Map<String, Map<String, Object>> data = objectMapper.readValue(
                    legacyFile.toFile(),
                    new TypeReference<Map<String, Map<String, Object>>>() {}
            );
            
            lock.writeLock().lock();
            try {
                for (Map.Entry<String, Map<String, Object>> entry : data.entrySet()) {
                    String cid = entry.getKey();
                    Map<String, Object> convMap = entry.getValue();
                    
                    ConversationDetails conv = new ConversationDetails();
                    conv.setId(cid);
                    conv.setTitle((String) convMap.getOrDefault("title", "New chat"));
                    
                    Object updatedAtObj = convMap.get("updated_at");
                    long updatedAt = Instant.now().getEpochSecond();
                    if (updatedAtObj instanceof Number) {
                        updatedAt = ((Number) updatedAtObj).longValue();
                    }
                    conv.setUpdatedAt(updatedAt);

                    Object msgsObj = convMap.get("messages");
                    List<ConversationMessage> msgs = new ArrayList<>();
                    if (msgsObj instanceof List) {
                        for (Object m : (List<?>) msgsObj) {
                            if (m instanceof Map) {
                                Map<?, ?> mData = (Map<?, ?>) m;
                                ConversationMessage msg = new ConversationMessage();
                                msg.setRole((String) mData.get("role"));
                                msg.setContent((String) mData.get("content"));
                                Object createdObj = mData.get("created_at");
                                long created = updatedAt;
                                if (createdObj instanceof Number) {
                                    created = ((Number) createdObj).longValue();
                                }
                                msg.setCreatedAt(created);
                                msgs.add(msg);
                            }
                        }
                    }
                    conv.setMessages(msgs);
                    saveConv(conv);
                }

                // Rename legacy file to back it up
                Path backup = legacyFile.resolveSibling("conversations.legacy.json");
                Files.move(legacyFile, backup, StandardCopyOption.REPLACE_EXISTING);
                log.info("Legacy conversation migration complete. Backup saved to {}", backup);
            } finally {
                lock.writeLock().unlock();
            }
        } catch (IOException e) {
            log.error("Failed to migrate legacy conversations", e);
        }
    }
}
