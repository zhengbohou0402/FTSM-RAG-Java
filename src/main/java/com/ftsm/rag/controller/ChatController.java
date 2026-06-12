package com.ftsm.rag.controller;

import com.ftsm.rag.model.ChatRequest;
import com.ftsm.rag.service.ChatService;
import com.ftsm.rag.service.SettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;
    private final SettingsService settingsService;

    public ChatController(ChatService chatService, SettingsService settingsService) {
        this.chatService = chatService;
        this.settingsService = settingsService;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<Flux<String>> chat(@RequestBody ChatRequest payload) {
        if (!settingsService.isDashScopeConfigured()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, 
                    "DASHSCOPE_API_KEY is not configured. Please set it in /settings.");
        }

        String message = payload.getMessage();
        if (message == null || message.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message is required.");
        }

        String conversationId = payload.getConversationId();
        if (conversationId == null || conversationId.trim().isEmpty()) {
            conversationId = UUID.randomUUID().toString();
        } else if (!conversationId.matches("^[0-9a-fA-F-]{1,64}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid conversation id.");
        }

        log.info("Starting chat stream for conversation: {}", conversationId);

        Flux<String> stream = chatService.streamChatAnswer(message.trim(), conversationId);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Cache-Control", "no-cache");
        headers.set("X-Accel-Buffering", "no");
        headers.set("X-Conversation-Id", conversationId);

        return new ResponseEntity<>(stream, headers, HttpStatus.OK);
    }
}
