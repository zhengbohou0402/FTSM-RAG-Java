package com.ftsm.rag.service;

import com.ftsm.rag.config.AppConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class DashScopeHttpClient {

    private final AppConfig appConfig;
    private final SettingsService settingsService;
    private final WebClient webClient;

    public DashScopeHttpClient(AppConfig appConfig, SettingsService settingsService, WebClient.Builder webClientBuilder) {
        this.appConfig = appConfig;
        this.settingsService = settingsService;
        this.webClient = webClientBuilder.build();
    }

    /**
     * Calls the DashScope Rerank API to score documents.
     * Returns a list of sorted indices.
     */
    public Mono<List<Integer>> rerank(String query, List<String> documents, int topN) {
        String apiKey = settingsService.getApiKey();
        String baseUrl = settingsService.getBaseUrl();
        String model = appConfig.getDashscope().getRerankModel();

        if (apiKey.isEmpty() || documents.isEmpty()) {
            return Mono.just(new ArrayList<>());
        }

        // Adjust endpoint based on base URL
        String rerankUrl = baseUrl + "/services/rerank/text-rerank/text-rerank";
        
        RerankRequest request = new RerankRequest();
        request.setModel(model);
        request.getInput().setQuery(query);
        request.getInput().setDocuments(documents);
        request.getParameters().setTopN(topN);

        log.info("Sending Rerank request to {}, model={}, docs count={}", rerankUrl, model, documents.size());

        return webClient.post()
                .uri(rerankUrl)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(RerankResponse.class)
                .map(response -> {
                    List<Integer> sortedIndices = new ArrayList<>();
                    if (response != null && response.getOutput() != null && response.getOutput().getResults() != null) {
                        for (RerankResponse.RerankResult result : response.getOutput().getResults()) {
                            sortedIndices.add(result.getIndex());
                        }
                    }
                    return sortedIndices;
                })
                .onErrorResume(e -> {
                    log.error("DashScope Rerank API call failed", e);
                    return Mono.just(new ArrayList<>());
                });
    }

    /**
     * Calls Qwen-VL model to extract text from a base64 encoded image.
     */
    public Mono<String> extractTextFromImage(String base64Image, String promptText) {
        String apiKey = settingsService.getApiKey();
        String baseUrl = settingsService.getBaseUrl();
        String model = appConfig.getDashscope().getImageModel();

        if (apiKey.isEmpty()) {
            return Mono.error(new IllegalStateException("API key not configured"));
        }

        String vlUrl = baseUrl + "/services/aigc/multimodal-generation/generation";

        // Construct request payload
        MultiModalRequest request = new MultiModalRequest();
        request.setModel(model);
        
        MultiModalRequest.Message message = new MultiModalRequest.Message();
        message.setRole("user");
        
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("image", "data:image/jpeg;base64," + base64Image));
        content.add(Map.of("text", promptText));
        message.setContent(content);
        
        request.getInput().setMessages(List.of(message));

        log.info("Sending MultiModal text extraction request to {}, model={}", vlUrl, model);

        return webClient.post()
                .uri(vlUrl)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(MultiModalResponse.class)
                .map(response -> {
                    if (response != null && response.getOutput() != null && response.getOutput().getChoices() != null 
                            && !response.getOutput().getChoices().isEmpty()) {
                        MultiModalResponse.Choice choice = response.getOutput().getChoices().get(0);
                        if (choice.getMessage() != null && choice.getMessage().getContent() != null 
                                && !choice.getMessage().getContent().isEmpty()) {
                            Map<String, Object> textObj = choice.getMessage().getContent().get(0);
                            if (textObj.containsKey("text")) {
                                return (String) textObj.get("text");
                            }
                        }
                    }
                    return "";
                })
                .onErrorResume(e -> {
                    log.error("DashScope Multimodal API call failed", e);
                    return Mono.just("");
                });
    }

    // --- DTOs for Rerank ---

    @Data
    public static class RerankRequest {
        private String model;
        private Input input = new Input();
        private Parameters parameters = new Parameters();

        @Data
        public static class Input {
            private String query;
            private List<String> documents = new ArrayList<>();
        }

        @Data
        public static class Parameters {
            private int topN;
        }
    }

    @Data
    public static class RerankResponse {
        private Output output;
        private Object usage;
        private String requestId;

        @Data
        public static class Output {
            private List<RerankResult> results;
        }

        @Data
        public static class RerankResult {
            private int index;
            private double relevanceScore;
        }
    }

    // --- DTOs for Multimodal Qwen-VL ---

    @Data
    public static class MultiModalRequest {
        private String model;
        private Input input = new Input();

        @Data
        public static class Input {
            private List<Message> messages = new ArrayList<>();
        }

        @Data
        public static class Message {
            private String role;
            private List<Map<String, Object>> content = new ArrayList<>();
        }
    }

    @Data
    public static class MultiModalResponse {
        private Output output;
        private String requestId;

        @Data
        public static class Output {
            private List<Choice> choices;
        }

        @Data
        public static class Choice {
            private String finishReason;
            private Message message;
        }

        @Data
        public static class Message {
            private String role;
            private List<Map<String, Object>> content;
        }
    }
}
