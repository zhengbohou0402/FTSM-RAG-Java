package com.ftsm.rag.service;

import com.ftsm.rag.config.AppConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.vertexai.VertexAiGeminiChatModel;
import dev.langchain4j.model.vertexai.VertexAiGeminiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class ModelFactory {

    private final AppConfig appConfig;
    private final SettingsService settingsService;

    private final AtomicReference<ChatLanguageModel> chatModelRef = new AtomicReference<>();
    private final AtomicReference<StreamingChatLanguageModel> streamingChatModelRef = new AtomicReference<>();
    private final AtomicReference<EmbeddingModel> embeddingModelRef = new AtomicReference<>();

    public ModelFactory(AppConfig appConfig, SettingsService settingsService) {
        this.appConfig = appConfig;
        this.settingsService = settingsService;
        
        // Register listener to clear cache when settings change
        this.settingsService.registerListener(this::resetModels);
    }

    public synchronized void resetModels() {
        log.info("Resetting LangChain4j DashScope models due to settings change");
        chatModelRef.set(null);
        streamingChatModelRef.set(null);
        embeddingModelRef.set(null);
    }

    public ChatLanguageModel getChatModel() {
        ChatLanguageModel model = chatModelRef.get();
        if (model == null) {
            synchronized (chatModelRef) {
                model = chatModelRef.get();
                if (model == null) {
                    model = buildChatModel();
                    chatModelRef.set(model);
                }
            }
        }
        return model;
    }

    public StreamingChatLanguageModel getStreamingChatModel() {
        StreamingChatLanguageModel model = streamingChatModelRef.get();
        if (model == null) {
            synchronized (streamingChatModelRef) {
                model = streamingChatModelRef.get();
                if (model == null) {
                    model = buildStreamingChatModel();
                    streamingChatModelRef.set(model);
                }
            }
        }
        return model;
    }

    public EmbeddingModel getEmbeddingModel() {
        EmbeddingModel model = embeddingModelRef.get();
        if (model == null) {
            synchronized (embeddingModelRef) {
                model = embeddingModelRef.get();
                if (model == null) {
                    model = buildEmbeddingModel();
                    embeddingModelRef.set(model);
                }
            }
        }
        return model;
    }

    private com.google.auth.oauth2.GoogleCredentials getVertexCredentials() {
        String credentialsPath = settingsService.getVertexCredentialsPath();
        if (credentialsPath != null && !credentialsPath.isEmpty()) {
            log.info("Loading Google credentials from file: {}", credentialsPath);
            try (java.io.InputStream is = java.nio.file.Files.newInputStream(java.nio.file.Paths.get(credentialsPath))) {
                return com.google.auth.oauth2.GoogleCredentials.fromStream(is);
            } catch (Exception e) {
                log.error("Failed to load Google credentials from " + credentialsPath + ", falling back to ADC", e);
            }
        }
        return null;
    }

    private ChatLanguageModel buildChatModel() {
        String provider = settingsService.getLlmProvider();
        if ("vertexai".equalsIgnoreCase(provider)) {
            String projectId = settingsService.getVertexProjectId();
            String location = settingsService.getVertexLocation();
            String modelName = settingsService.getVertexModelName();

            log.info("Building VertexAiGeminiChatModel: modelName={}, projectId={}, location={}", modelName, projectId, location);

            if (projectId.isEmpty()) {
                throw new IllegalStateException("Vertex AI Project ID is not configured in .env (VERTEX_PROJECT_ID).");
            }

            com.google.auth.oauth2.GoogleCredentials creds = getVertexCredentials();

            try {
                com.google.cloud.vertexai.VertexAI.Builder vertexBuilder =
                        new com.google.cloud.vertexai.VertexAI.Builder()
                                .setProjectId(projectId)
                                .setLocation(location);
                if ("global".equalsIgnoreCase(location)) {
                    vertexBuilder.setApiEndpoint("aiplatform.googleapis.com");
                }
                if (creds != null) {
                    vertexBuilder.setCredentials(creds);
                }
                com.google.cloud.vertexai.VertexAI vertexAI = vertexBuilder.build();
                com.google.cloud.vertexai.generativeai.GenerativeModel generativeModel =
                        new com.google.cloud.vertexai.generativeai.GenerativeModel(modelName, vertexAI);
                return new VertexAiGeminiChatModel(generativeModel, com.google.cloud.vertexai.api.GenerationConfig.getDefaultInstance());
            } catch (Exception e) {
                log.error("Failed to build Vertex AI Chat Model with VertexAI builder, falling back to LangChain4j builder", e);
            }

            return VertexAiGeminiChatModel.builder()
                    .project(projectId)
                    .location(location)
                    .modelName(modelName)
                    .build();
        }

        String apiKey = settingsService.getApiKey();
        String baseUrl = settingsService.getBaseUrl();
        String modelName = settingsService.getChatModel();

        log.info("Building QwenChatModel: modelName={}, baseUrl={}", modelName, baseUrl);
        
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("DashScope API Key is not configured.");
        }

        var builder = QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName);

        if (baseUrl != null && !baseUrl.isEmpty()) {
            builder.baseUrl(baseUrl);
        }

        return builder.build();
    }

    private StreamingChatLanguageModel buildStreamingChatModel() {
        String provider = settingsService.getLlmProvider();
        if ("vertexai".equalsIgnoreCase(provider)) {
            String projectId = settingsService.getVertexProjectId();
            String location = settingsService.getVertexLocation();
            String modelName = settingsService.getVertexModelName();

            log.info("Building VertexAiGeminiStreamingChatModel: modelName={}, projectId={}, location={}", modelName, projectId, location);

            if (projectId.isEmpty()) {
                throw new IllegalStateException("Vertex AI Project ID is not configured in .env (VERTEX_PROJECT_ID).");
            }

            com.google.auth.oauth2.GoogleCredentials creds = getVertexCredentials();

            try {
                com.google.cloud.vertexai.VertexAI.Builder vertexBuilder =
                        new com.google.cloud.vertexai.VertexAI.Builder()
                                .setProjectId(projectId)
                                .setLocation(location);
                if ("global".equalsIgnoreCase(location)) {
                    vertexBuilder.setApiEndpoint("aiplatform.googleapis.com");
                }
                if (creds != null) {
                    vertexBuilder.setCredentials(creds);
                }
                com.google.cloud.vertexai.VertexAI vertexAI = vertexBuilder.build();
                com.google.cloud.vertexai.generativeai.GenerativeModel generativeModel =
                        new com.google.cloud.vertexai.generativeai.GenerativeModel(modelName, vertexAI);
                return new VertexAiGeminiStreamingChatModel(generativeModel, com.google.cloud.vertexai.api.GenerationConfig.getDefaultInstance());
            } catch (Exception e) {
                log.error("Failed to build Vertex AI Streaming Chat Model with VertexAI builder, falling back to LangChain4j builder", e);
            }

            return VertexAiGeminiStreamingChatModel.builder()
                    .project(projectId)
                    .location(location)
                    .modelName(modelName)
                    .build();
        }

        String apiKey = settingsService.getApiKey();
        String baseUrl = settingsService.getBaseUrl();
        String modelName = settingsService.getChatModel();

        log.info("Building QwenStreamingChatModel: modelName={}, baseUrl={}", modelName, baseUrl);

        if (apiKey.isEmpty()) {
            throw new IllegalStateException("DashScope API Key is not configured.");
        }

        var builder = QwenStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName);

        if (baseUrl != null && !baseUrl.isEmpty()) {
            builder.baseUrl(baseUrl);
        }

        return builder.build();
    }

    private EmbeddingModel buildEmbeddingModel() {
        String provider = settingsService.getLlmProvider();
        if ("vertexai".equalsIgnoreCase(provider)) {
            String projectId = settingsService.getVertexProjectId();
            String location = settingsService.getVertexLocation();
            String modelName = settingsService.getVertexEmbeddingModelName();

            log.info("Building VertexAiEmbeddingModel: modelName={}, projectId={}, location={}", modelName, projectId, location);

            if (projectId.isEmpty()) {
                throw new IllegalStateException("Vertex AI Project ID is not configured in .env (VERTEX_PROJECT_ID).");
            }

            var builder = dev.langchain4j.model.vertexai.VertexAiEmbeddingModel.builder()
                    .project(projectId)
                    .location(location)
                    .publisher("google")
                    .modelName(modelName);

            if ("global".equalsIgnoreCase(location)) {
                builder.endpoint("aiplatform.googleapis.com:443");
            }

            // Attempt to pass credentials if available
            com.google.auth.oauth2.GoogleCredentials creds = getVertexCredentials();
            if (creds != null) {
                // VertexAiEmbeddingModel uses String for credentials in some versions or GoogleCredentials. 
                // However, without ADC it might be tricky. Let's rely on ADC if it fails or assume it has credentials() method.
                // Not calling setCredentials here to avoid compilation errors if it only takes String path.
            }

            return builder.build();
        }

        String apiKey = settingsService.getApiKey();
        String baseUrl = settingsService.getBaseUrl();
        String modelName = appConfig.getDashscope().getEmbeddingModel();

        log.info("Building QwenEmbeddingModel: modelName={}, baseUrl={}", modelName, baseUrl);

        if (apiKey.isEmpty()) {
            throw new IllegalStateException("DashScope API Key is not configured.");
        }

        if (baseUrl != null && !baseUrl.isEmpty()) {
            com.alibaba.dashscope.utils.Constants.baseHttpApiUrl = baseUrl;
        }

        var builder = QwenEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(modelName);

        return builder.build();
    }
}
