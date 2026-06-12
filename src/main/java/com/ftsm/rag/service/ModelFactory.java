package com.ftsm.rag.service;

import com.ftsm.rag.config.AppConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
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

    private ChatLanguageModel buildChatModel() {
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
