package com.ftsm.rag.service;

import com.ftsm.rag.config.AppConfig;
import com.ftsm.rag.model.ConversationMessage;
import com.ftsm.rag.model.ManifestData;
import com.ftsm.rag.store.ConversationStore;
import com.ftsm.rag.store.DocumentManifestManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class ChatService {

    private final AppConfig appConfig;
    private final ConversationStore conversationStore;
    private final SemanticCacheService semanticCacheService;
    private final ToolRoutingAgent toolRoutingAgent;
    private final DocumentManifestManager manifestManager;
    private final SettingsService settingsService;

    public ChatService(AppConfig appConfig, ConversationStore conversationStore,
                       SemanticCacheService semanticCacheService, ToolRoutingAgent toolRoutingAgent,
                       DocumentManifestManager manifestManager, SettingsService settingsService) {
        this.appConfig = appConfig;
        this.conversationStore = conversationStore;
        this.semanticCacheService = semanticCacheService;
        this.toolRoutingAgent = toolRoutingAgent;
        this.manifestManager = manifestManager;
        this.settingsService = settingsService;
    }

    private String getCacheNamespace() {
        String baseUrl = settingsService.getBaseUrl();
        String region = baseUrl.contains("intl") ? "intl" : "china";
        String chatModel = settingsService.getChatModel();
        
        ManifestData manifest = manifestManager.loadManifest();
        int indexVersion = manifest.getIndex() != null ? manifest.getIndex().getVersion() : 0;
        
        return String.format("v2|region=%s|chat=%s|index=%d", region, chatModel, indexVersion);
    }

    private void saveHistory(String conversationId, String query, String answer) {
        String title = query.length() <= 40 ? query : query.substring(0, 40) + "...";
        conversationStore.appendTurn(conversationId, query, answer, title);
    }

    public Flux<String> streamChatAnswer(String message, String conversationId) {
        return Flux.defer(() -> {
            String finalConvId = conversationId != null
                    ? conversationId
                    : java.util.UUID.randomUUID().toString();

            int maxHistoryTurns = appConfig.getConversation().getMaxHistoryTurns();
            List<ConversationMessage> recentHistory = conversationStore.recentMessages(
                    finalConvId,
                    maxHistoryTurns
            );
            String namespace = getCacheNamespace();

            if (recentHistory.isEmpty()) {
                SemanticCacheService.CacheResult cacheResult = semanticCacheService.get(message, namespace);
                if (cacheResult.isHit()
                        && cacheResult.getAnswer() != null
                        && !cacheResult.getAnswer().contains("__THINK__")) {
                    saveHistory(finalConvId, message, cacheResult.getAnswer());
                    return Flux.just(
                            "__THINK__Answering from cache...__ENDTHINK__",
                            cacheResult.getAnswer()
                    );
                }
            }

            List<String> resultChunks = new ArrayList<>();
            AtomicBoolean hadError = new AtomicBoolean(false);

            return toolRoutingAgent.executeStream(message, recentHistory)
                    .doOnNext(chunk -> {
                        if (chunk != null && !chunk.startsWith("__THINK__")) {
                            resultChunks.add(chunk);
                        }
                    })
                    .onErrorResume(err -> {
                        hadError.set(true);
                        String detail = err.getMessage() == null
                                ? err.getClass().getSimpleName()
                                : err.getMessage();
                        String errMsg = "\n\n[Error] " + detail;
                        return Flux.just(errMsg);
                    })
                    .doOnComplete(() -> {
                        if (hadError.get()) {
                            return;
                        }
                        String finalAnswer = String.join("", resultChunks).trim();
                        if (!finalAnswer.isEmpty()) {
                            if (recentHistory.isEmpty()) {
                                semanticCacheService.set(message, finalAnswer, namespace);
                            }
                            saveHistory(finalConvId, message, finalAnswer);
                        }
                    });
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
