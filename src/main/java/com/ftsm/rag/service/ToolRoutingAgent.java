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

/**
 * This is a single-step tool-routing dispatcher, not a multi-step ReAct loop.
 * 原因是当前知识域下单跳检索+生成已经够用，用循环换来的多跳能力对延迟和可预测性的代价不划算。
 */
@Slf4j
@Service
public class ToolRoutingAgent {

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

    private static final ToolSpecification WEB_SEARCH_TOOL = ToolSpecification.builder()
            .name("web_search")
            .description("Search the internet for real-time information or general knowledge outside the local FTSM/UKM knowledge base. Use this for weather, current events, or non-university factual questions.")
            .addParameter(
                    "query",
                    JsonSchemaProperty.STRING,
                    JsonSchemaProperty.description("A standalone search query."))
            .build();

    private static final String ROUTER_PROMPT = """
            You are the tool-decision step of an assistant.
            Decide whether the user's latest message needs the rag_search tool, the web_search tool, or no tool.
            
            Routing Rules:
            1. Call `rag_search` whenever the answer depends on UKM or FTSM facts. If the user's message is a follow-up to a previous UKM/FTSM context, route to `rag_search` even if the word "UKM" or "FTSM" is missing. Put a complete standalone search query in the tool call.
            2. Call `web_search` if the user explicitly asks to "search" (e.g., "搜索一下", "查一下最新的") or asks about real-time events, weather, or general knowledge outside UKM/FTSM.
            3. Do not rely on model memory for institution-specific facts.
            4. Do not call any tool for greetings, thanks, casual conversation, writing help, or translation. If no tool is needed, respond briefly so the next phase can answer directly.
            
            Examples:
            - User: "2024年FTSM招生人数" -> Action: rag_search
            - User: "今天吉隆坡天气" -> Action: web_search
            - User: "帮我查一下最新的英伟达股价" -> Action: web_search
            - User: "谢谢" -> Action: no tool
            """;

    private final ModelFactory modelFactory;
    private final RagService ragService;
    private final SystemPromptService systemPromptService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolRoutingAgent(ModelFactory modelFactory, RagService ragService,
                      SystemPromptService systemPromptService) {
        this.modelFactory = modelFactory;
        this.ragService = ragService;
        this.systemPromptService = systemPromptService;
    }

    public Flux<String> executeStream(String query, List<ConversationMessage> history) {
        List<ConversationMessage> safeHistory = history == null ? List.of() : history;
        return decide(query, safeHistory)
                .flatMapMany(decision -> {
                    if ("rag".equals(decision.action())) {
                        return Flux.concat(
                                Flux.just("__THINK__Searching knowledge base...__ENDTHINK__"),
                                ragService.streamAnswer(decision.query()));
                    } else if ("web".equals(decision.action())) {
                        return Flux.concat(
                                Flux.just("__THINK__Searching the web for latest info...__ENDTHINK__"),
                                streamDirectAnswerWithContext(query, decision.webContext(), safeHistory));
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

            Response<AiMessage> response = modelFactory.getChatModel().generate(messages, List.of(RAG_TOOL, WEB_SEARCH_TOOL));
            AiMessage message = response.content();
            if (message != null && message.hasToolExecutionRequests()) {
                for (ToolExecutionRequest request : message.toolExecutionRequests()) {
                    if ("rag_search".equals(request.name())) {
                        return new AgentDecision("rag", toolQuery(request, query, history), null);
                    } else if ("web_search".equals(request.name())) {
                        String webQuery = toolQuery(request, query, history);
                        String webContext = performWebSearch(webQuery);
                        return new AgentDecision("web", webQuery, webContext);
                    }
                }
            }
            return new AgentDecision("direct", query, null);
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(error -> {
            log.warn("Agent tool decision failed, using conservative fallback: {}", error.getMessage());
            boolean casual = isCasualMessage(query);
            return Mono.just(new AgentDecision(
                    casual ? "direct" : "rag",
                    contextualizeQuery(query, history), null));
        });
    }

    private String performWebSearch(String query) {
        try {
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.connect("https://html.duckduckgo.com/html/")
                    .data("q", query)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .get();
            StringBuilder results = new StringBuilder();
            org.jsoup.select.Elements resultBlocks = doc.select(".result");
            for (int i = 0; i < Math.min(3, resultBlocks.size()); i++) {
                org.jsoup.nodes.Element block = resultBlocks.get(i);
                String title = block.select(".result__title a").text();
                String url = block.select(".result__title a").attr("href");
                // Clean up DDG redirect URL if needed, but raw is fine too
                if (url.startsWith("//duckduckgo.com/l/?uddg=")) {
                    try {
                        url = java.net.URLDecoder.decode(url.substring(25).split("&")[0], "UTF-8");
                    } catch (Exception ignored) {}
                } else if (url.startsWith("/")) {
                    url = "https://duckduckgo.com" + url;
                }
                
                String snippet = block.select(".result__snippet").text();
                if (!title.isEmpty() && !url.isEmpty()) {
                    results.append("[").append(i + 1).append("] ").append(title).append("\n")
                           .append("URL: ").append(url).append("\n")
                           .append(snippet).append("\n\n");
                }
            }
            if (results.length() == 0) {
                return "No search results found for: " + query;
            }
            return "Web Search Results:\n" + results.toString();
        } catch (Exception e) {
            log.warn("Web search failed: {}", e.getMessage());
            return "Failed to retrieve web search results.";
        }
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
        return streamDirectAnswerWithContext(query, null, history);
    }

    private Flux<String> streamDirectAnswerWithContext(String query, String webContext, List<ConversationMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPromptService.getPrompt()));
        addHistory(messages, history);
        
        String finalQuery = query;
        if (webContext != null) {
            finalQuery = "Context from web search:\n" + webContext + "\n\nQuestion: " + query;
        }
        messages.add(UserMessage.from(finalQuery));

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

    private record AgentDecision(String action, String query, String webContext) {
    }
}
