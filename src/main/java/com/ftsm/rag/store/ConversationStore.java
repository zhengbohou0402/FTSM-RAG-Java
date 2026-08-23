package com.ftsm.rag.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ftsm.rag.config.AppConfig;
import com.ftsm.rag.model.ConversationDetails;
import com.ftsm.rag.model.ConversationIndexItem;
import com.ftsm.rag.model.ConversationMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ConversationStore {

    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    private static final String INDEX_KEY = "conversations:index";
    private static final String CONV_PREFIX = "conversation:";

    private static final Pattern SAFE_ID_RE = Pattern.compile("^[0-9a-fA-F-]{1,64}$");

    public ConversationStore(AppConfig appConfig, StringRedisTemplate redisTemplate) {
        this.appConfig = appConfig;
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    private void requireValidConversationId(String conversationId) {
        if (conversationId == null || !SAFE_ID_RE.matcher(conversationId).matches()) {
            throw new IllegalArgumentException("Invalid conversation id");
        }
    }

    private String getConvKey(String conversationId) {
        return CONV_PREFIX + conversationId;
    }

    public List<ConversationIndexItem> listItems(Integer limit) {
        List<ConversationIndexItem> items = loadIndex();
        items.sort((a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt()));
        if (limit != null && limit > 0 && limit < items.size()) {
            return items.subList(0, limit);
        }
        return items;
    }

    public ConversationDetails get(String conversationId) {
        requireValidConversationId(conversationId);
        String json = redisTemplate.opsForValue().get(getConvKey(conversationId));
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ConversationDetails.class);
        } catch (IOException e) {
            log.error("Failed to read conversation details for {}", conversationId, e);
            return null;
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
        saveConv(conv);
        return conv;
    }

    public boolean delete(String conversationId) {
        requireValidConversationId(conversationId);
        Boolean deleted = redisTemplate.delete(getConvKey(conversationId));
        if (deleted != null && deleted) {
            redisTemplate.opsForHash().delete(INDEX_KEY, conversationId);
            return true;
        }
        return false;
    }

    public void deleteAll() {
        Set<String> keys = redisTemplate.keys(CONV_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        redisTemplate.delete(INDEX_KEY);
    }

    public void appendTurn(String conversationId, String userContent, String assistantContent, String title) {
        requireValidConversationId(conversationId);
        long now = Instant.now().getEpochSecond();
        
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
    }

    // --- Private Helper Methods ---

    private List<ConversationIndexItem> loadIndex() {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(INDEX_KEY);
        List<ConversationIndexItem> items = new ArrayList<>();
        for (Object value : entries.values()) {
            try {
                items.add(objectMapper.readValue((String) value, ConversationIndexItem.class));
            } catch (IOException e) {
                log.warn("Failed to parse index item", e);
            }
        }
        return items;
    }

    private void saveConv(ConversationDetails conv) {
        String cid = conv.getId();
        requireValidConversationId(cid);
        
        try {
            String content = objectMapper.writeValueAsString(conv);
            redisTemplate.opsForValue().set(getConvKey(cid), content);

            ConversationIndexItem item = new ConversationIndexItem();
            item.setId(cid);
            item.setTitle(conv.getTitle());
            item.setUpdatedAt(conv.getUpdatedAt());
            
            String itemJson = objectMapper.writeValueAsString(item);
            redisTemplate.opsForHash().put(INDEX_KEY, cid, itemJson);
            
            pruneIndex();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save conversation " + cid, e);
        }
    }

    private void pruneIndex() {
        int maxConvs = appConfig.getConversation().getMaxConversations();
        List<ConversationIndexItem> items = loadIndex();
        if (items.size() <= maxConvs) {
            return;
        }
        
        items.sort((a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt()));
        List<ConversationIndexItem> toDelete = items.subList(maxConvs, items.size());
        
        if (!toDelete.isEmpty()) {
            Object[] hashKeys = toDelete.stream().map(ConversationIndexItem::getId).toArray();
            redisTemplate.opsForHash().delete(INDEX_KEY, hashKeys);
            
            List<String> convKeys = toDelete.stream()
                    .map(item -> getConvKey(item.getId()))
                    .collect(Collectors.toList());
            redisTemplate.delete(convKeys);
        }
    }
}
