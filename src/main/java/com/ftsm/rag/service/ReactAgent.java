package com.ftsm.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ftsm.rag.model.ConversationMessage;
import dev.langchain4j.agent.tool.JsonSchemaProperty;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ReactAgent {

    private static final ToolSpecification RAG_TOOL = ToolSpecification.builder()
            .name("rag_search")
            .description("Search the local UKM FTSM knowledge base. Use this for factual questions "
                    + "about FTSM or UKM programmes, admission, calendars, timetables, courses, "
                    + "facilities, staff, student systems, visas, campus services, or prior retrieved facts.")
            .addParameter(
                    "query",
                    JsonSchemaProperty.STRING,
                    JsonSchemaProperty.description(
                            "A standalone retrieval query containing any context needed from chat history."))
            .build();

    private static final String ROUTER_PROMPT = """
            You are the tool-decision step of an assistant.
            Decide whether the user's latest message needs the rag_search tool.
            Call rag_search whenever the answer depends on UKM or FTSM facts, including a follow-up
            to an earlier UKM/FTSM question. Put a complete standalone search query in the tool call.
            Do not rely on model memory for institution-specific facts.
            Do not call the tool for greetings, thanks, casual conversation, writing help,
            translation, or general knowledge that is unrelated to UKM/FTSM.
            If no tool is needed, respond briefly so the next streaming phase can answer directly.
            """;

    private final ModelFactory modelFactory;
    private final RagService ragService;
    private final SystemPromptService systemPromptService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReactAgent(ModelFactory modelFactory, RagService ragService,
                      SystemPromptService systemPromptService) {
        this.modelFactory = modelFactory;
        this.ragService = ragService;
        this.systemPromptService = systemPromptService;
    }

    public Flux<String> executeStream(String query, List<ConversationMessage> history) {
        List<ConversationMessage> safeHistory = history == null ? List.of() : history;
        return decide(query, safeHistory)
                .flatMapMany(decision -> {
                    if (decision.useRag()) {
                        return Flux.concat(
                                Flux.just("__THINK__Searching knowledge base...__ENDTHINK__"),
                                ragService.streamAnswer(decision.query()));
                    }
                    return streamDirectAnswer(query, safeHistory);
                });
    }

    private Mono<AgentDecision> decide(String query, List<ConversationMessage> history) {
        return Mono.fromCallable(() -> {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(ROUTER_PROMPT));
            addHistory(messages, history);
            messages.add(UserMessage.from(query));

            Response<AiMessage> response = modelFactory.getChatModel().generate(messages, RAG_TOOL);
            AiMessage message = response.content();
            if (message != null && message.hasToolExecutionRequests()) {
                for (ToolExecutionRequest request : message.toolExecutionRequests()) {
                    if ("rag_search".equals(request.name())) {
                        return new AgentDecision(true, toolQuery(request, query, history));
                    }
                }
            }
            return new AgentDecision(false, query);
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(error -> {
            log.warn("Agent tool decision failed, using conservative fallback: {}", error.getMessage());
            return Mono.just(new AgentDecision(
                    !isCasualMessage(query),
                    contextualizeQuery(query, history)));
        });
    }

    private String toolQuery(ToolExecutionRequest request, String original,
                             List<ConversationMessage> history) {
        try {
            JsonNode arguments = objectMapper.readTree(request.arguments());
            String query = arguments.path("query").asText("").trim();
            if (!query.isEmpty()) {
                return query;
            }
        } catch (Exception error) {
            log.warn("Could not parse rag_search arguments: {}", error.getMessage());
        }
        return contextualizeQuery(original, history);
    }

    private Flux<String> streamDirectAnswer(String query, List<ConversationMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPromptService.getPrompt()));
        addHistory(messages, history);
        messages.add(UserMessage.from(query));

        return Flux.<String>create(sink -> {
            try {
                modelFactory.getStreamingChatModel().generate(
                        messages,
                        new StreamingResponseHandler<AiMessage>() {
                            @Override
                            public void onNext(String token) {
                                sink.next(token);
                            }

                            @Override
                            public void onComplete(Response<AiMessage> response) {
                                sink.complete();
                            }

                            @Override
                            public void onError(Throwable error) {
                                sink.error(error);
                            }
                        });
            } catch (Exception error) {
                sink.error(error);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void addHistory(List<ChatMessage> messages, List<ConversationMessage> history) {
        int start = Math.max(0, history.size() - 20);
        for (int i = start; i < history.size(); i++) {
            ConversationMessage message = history.get(i);
            if ("user".equalsIgnoreCase(message.getRole())) {
                messages.add(UserMessage.from(message.getContent()));
            } else if ("assistant".equalsIgnoreCase(message.getRole())) {
                messages.add(AiMessage.from(message.getContent()));
            }
        }
    }

    static boolean isCasualMessage(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase();
        return normalized.matches(
                "^(hi|hello|hey|good morning|good afternoon|good evening|thanks|thank you|"
                        + "你好|您好|谢谢|多谢)[!.。！ ]*$"
        );
    }

    static String contextualizeQuery(String query, List<ConversationMessage> history) {
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

    private record AgentDecision(boolean useRag, String query) {
    }
}
