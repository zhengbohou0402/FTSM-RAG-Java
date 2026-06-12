package com.ftsm.rag.service;

import com.ftsm.rag.model.ConversationMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Slf4j
@Service
public class ReactAgent {

    private final ModelFactory modelFactory;
    private final RagService ragService;
    private final SystemPromptService systemPromptService;

    public ReactAgent(ModelFactory modelFactory, RagService ragService,
                      SystemPromptService systemPromptService) {
        this.modelFactory = modelFactory;
        this.ragService = ragService;
        this.systemPromptService = systemPromptService;
    }

    public interface SimpleStreamingAgent {
        TokenStream chat(String message);
    }

    public Flux<String> executeStream(String query, List<ConversationMessage> history) {
        if (!isCasualMessage(query)) {
            String retrievalQuery = contextualizeQuery(query, history);
            return Flux.concat(
                    Flux.just("__THINK__Searching knowledge base...__ENDTHINK__"),
                    ragService.ragSummarize(retrievalQuery).flux()
            );
        }

        return Flux.<String>create(sink -> {
            try {
                ChatMemory chatMemory = MessageWindowChatMemory.withMaxMessages(20);
                chatMemory.add(SystemMessage.from(systemPromptService.getPrompt()));

                // Add conversation history
                for (ConversationMessage msg : history) {
                    if ("user".equalsIgnoreCase(msg.getRole())) {
                        chatMemory.add(UserMessage.from(msg.getContent()));
                    } else {
                        chatMemory.add(AiMessage.from(msg.getContent()));
                    }
                }

                SimpleStreamingAgent agent = AiServices.builder(SimpleStreamingAgent.class)
                        .streamingChatLanguageModel(modelFactory.getStreamingChatModel())
                        .chatMemory(chatMemory)
                        .build();

                agent.chat(query)
                        .onNext(sink::next)
                        .onComplete(response -> sink.complete())
                        .onError(sink::error)
                        .start();

            } catch (Exception e) {
                log.error("Failed in ReactAgent executeStream", e);
                sink.error(e);
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private boolean isCasualMessage(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase();
        return normalized.matches(
                "^(hi|hello|hey|good morning|good afternoon|good evening|thanks|thank you|你好|您好|谢谢)[!.。！ ]*$"
        );
    }

    private String contextualizeQuery(String query, List<ConversationMessage> history) {
        if (history == null || history.isEmpty()) {
            return query;
        }
        String normalized = query.toLowerCase();
        boolean likelyFollowUp = query.length() < 40
                || normalized.matches(".*\\b(it|that|this|they|them|there|those|its)\\b.*")
                || normalized.matches(".*(这个|那个|它|他们|那里|上述|刚才).*");
        if (!likelyFollowUp) {
            return query;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            ConversationMessage message = history.get(i);
            if ("user".equalsIgnoreCase(message.getRole())) {
                return message.getContent() + "\nFollow-up question: " + query;
            }
        }
        return query;
    }
}
