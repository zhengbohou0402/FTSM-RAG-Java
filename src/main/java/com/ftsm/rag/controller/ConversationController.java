package com.ftsm.rag.controller;

import com.ftsm.rag.model.ConversationDetails;
import com.ftsm.rag.model.ConversationIndexItem;
import com.ftsm.rag.store.ConversationStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ConversationController {

    private final ConversationStore conversationStore;

    public ConversationController(ConversationStore conversationStore) {
        this.conversationStore = conversationStore;
    }

    @PostMapping("/conversations")
    public ConversationDetails createConversation() {
        String conversationId = UUID.randomUUID().toString();
        return conversationStore.create(conversationId);
    }

    @GetMapping("/conversations")
    public Map<String, List<ConversationIndexItem>> listConversations() {
        List<ConversationIndexItem> items = conversationStore.listItems(50);
        return Map.of("items", items);
    }

    @DeleteMapping("/conversations")
    public Map<String, Boolean> deleteAllConversations() {
        conversationStore.deleteAll();
        return Map.of("ok", true);
    }

    @DeleteMapping("/conversations/{conversationId}")
    public Map<String, Boolean> deleteConversation(@PathVariable String conversationId) {
        boolean found;
        try {
            found = conversationStore.delete(conversationId);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
        if (!found) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
        }
        return Map.of("ok", true);
    }

    @GetMapping("/conversations/{conversationId}")
    public ConversationDetails getConversation(@PathVariable String conversationId) {
        ConversationDetails conv;
        try {
            conv = conversationStore.get(conversationId);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
        if (conv == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
        }
        return conv;
    }
}
