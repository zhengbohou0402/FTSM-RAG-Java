package com.ftsm.rag.service;

import com.ftsm.rag.config.AppConfig;
import com.ftsm.rag.model.ConversationMessage;
import com.ftsm.rag.model.IndexState;
import com.ftsm.rag.model.ManifestData;
import com.ftsm.rag.store.ConversationStore;
import com.ftsm.rag.store.DocumentManifestManager;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    @Test
    void doesNotPersistPartialAnswersWhenTheModelFails() {
        AppConfig config = new AppConfig();
        ConversationStore conversationStore = mock(ConversationStore.class);
        SemanticCacheService cache = mock(SemanticCacheService.class);
        ReactAgent agent = mock(ReactAgent.class);
        DocumentManifestManager manifestManager = mock(DocumentManifestManager.class);
        SettingsService settings = mock(SettingsService.class);

        ConversationMessage priorMessage = new ConversationMessage("user", "earlier", 1L);
        when(conversationStore.recentMessages(anyString(), eq(config.getConversation().getMaxHistoryTurns())))
                .thenReturn(List.of(priorMessage));
        when(settings.getBaseUrl()).thenReturn("https://dashscope.aliyuncs.com/api/v1");
        when(settings.getChatModel()).thenReturn("qwen-turbo");
        ManifestData manifest = new ManifestData();
        manifest.setDocuments(new HashMap<>());
        manifest.setIndex(new IndexState());
        when(manifestManager.loadManifest()).thenReturn(manifest);
        when(agent.executeStream(anyString(), eq(List.of(priorMessage))))
                .thenReturn(Flux.concat(
                        Flux.just("partial answer"),
                        Flux.error(new IllegalStateException("model unavailable"))
                ));

        ChatService service = new ChatService(
                config,
                conversationStore,
                cache,
                agent,
                manifestManager,
                settings
        );

        StepVerifier.create(service.streamChatAnswer("question", "123e4567-e89b-12d3-a456-426614174000"))
                .expectNext("partial answer")
                .expectNext("\n\n[Error] model unavailable")
                .verifyComplete();

        verify(conversationStore, never()).appendTurn(
                anyString(),
                anyString(),
                anyString(),
                anyString()
        );
    }
}
