package com.ftsm.rag.service;

import com.ftsm.rag.config.AppConfig;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class VertexAiRankingClient {

    private final AppConfig appConfig;
    private final SettingsService settingsService;
    private final WebClient webClient;

    public VertexAiRankingClient(AppConfig appConfig, SettingsService settingsService, WebClient.Builder webClientBuilder) {
        this.appConfig = appConfig;
        this.settingsService = settingsService;
        this.webClient = webClientBuilder.build();
    }

    private GoogleCredentials getCredentials() throws Exception {
        String credentialsPath = settingsService.getVertexCredentialsPath();
        if (credentialsPath != null && !credentialsPath.isEmpty()) {
            try (InputStream is = Files.newInputStream(Paths.get(credentialsPath))) {
                return GoogleCredentials.fromStream(is).createScoped("https://www.googleapis.com/auth/cloud-platform");
            }
        }
        return GoogleCredentials.getApplicationDefault().createScoped("https://www.googleapis.com/auth/cloud-platform");
    }

    public Mono<List<Integer>> rerank(String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            return Mono.just(new ArrayList<>());
        }

        String projectId = settingsService.getVertexProjectId();
        String location = settingsService.getVertexLocation();
        String model = appConfig.getVertexai().getRankingModel();

        if (projectId == null || projectId.isEmpty()) {
            log.error("Vertex AI Project ID is not configured");
            return Mono.just(new ArrayList<>());
        }

        // Discovery Engine API endpoint
        String url = String.format("https://discoveryengine.googleapis.com/v1alpha/projects/%s/locations/%s/rankingConfigs/default_config:rank", projectId, location);

        return Mono.fromCallable(() -> {
                    GoogleCredentials creds = getCredentials();
                    creds.refreshIfExpired();
                    return creds.getAccessToken().getTokenValue();
                })
                .flatMap(token -> {
                    RankRequest request = new RankRequest();
                    request.setModel(model);
                    request.setQuery(query);
                    request.setTopN(topN);
                    request.setIgnoreRecordDetailsInResponse(true);

                    for (int i = 0; i < documents.size(); i++) {
                        request.getRecords().add(new RankRequest.Record(String.valueOf(i), documents.get(i)));
                    }

                    log.info("Sending Vertex AI Ranking request to {}, model={}, docs count={}", url, model, documents.size());

                    return webClient.post()
                            .uri(url)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(request)
                            .retrieve()
                            .bodyToMono(RankResponse.class)
                            .map(response -> {
                                List<Integer> sortedIndices = new ArrayList<>();
                                if (response != null && response.getRecords() != null) {
                                    for (RankResponse.RecordResult record : response.getRecords()) {
                                        try {
                                            sortedIndices.add(Integer.parseInt(record.getId()));
                                        } catch (NumberFormatException ignored) {}
                                    }
                                }
                                return sortedIndices;
                            });
                })
                .onErrorResume(e -> {
                    log.error("Vertex AI Ranking API call failed", e);
                    return Mono.just(new ArrayList<>());
                });
    }

    @Data
    public static class RankRequest {
        private String model;
        private String query;
        private List<Record> records = new ArrayList<>();
        private int topN;
        private boolean ignoreRecordDetailsInResponse;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Record {
            private String id;
            private String content;
        }
    }

    @Data
    public static class RankResponse {
        private List<RecordResult> records;

        @Data
        public static class RecordResult {
            private String id;
            private double score;
        }
    }
}
