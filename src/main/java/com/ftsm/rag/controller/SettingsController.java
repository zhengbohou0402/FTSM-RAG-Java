package com.ftsm.rag.controller;

import com.ftsm.rag.model.SettingsPayload;
import com.ftsm.rag.service.SettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class SettingsController {

    private static final List<String> CHINA_MODELS = List.of(
            "qwen3-max", "qwen3-max-latest", "qwen3-max-preview", "qwen3.6-max-preview",
            "qwen-max", "qwen-max-latest", "qwen3.6-plus", "qwen3.5-plus", "qwen-plus",
            "qwen-plus-latest", "qwen-turbo", "qwen-turbo-latest", "qwen-long", "qwen-long-latest"
    );

    private static final List<String> INTL_MODELS = List.of(
            "qwen3.6-max-preview", "qwen3.6-plus", "qwen3.5-plus", "qwen-plus",
            "qwen-plus-latest", "qwen-turbo", "qwen-turbo-latest"
    );

    private final SettingsService settingsService;
    private final WebClient webClient;

    public SettingsController(SettingsService settingsService, WebClient.Builder webClientBuilder) {
        this.settingsService = settingsService;
        this.webClient = webClientBuilder.build();
    }

    @GetMapping("/settings")
    public Map<String, String> getSettings() {
        Map<String, String> map = new HashMap<>();
        map.put("dashscope_api_key", maskApiKey(settingsService.getApiKey()));
        map.put("dashscope_base_url", settingsService.getBaseUrl());
        map.put("chat_model_name", settingsService.getChatModel());
        return map;
    }

    @PostMapping("/settings")
    public Map<String, Boolean> saveSettings(@RequestBody SettingsPayload payload) {
        String baseUrl = trimToEmpty(payload.getDashscopeBaseUrl());
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        if (!baseUrl.isEmpty()
                && !baseUrl.equals("https://dashscope.aliyuncs.com/api/v1")
                && !baseUrl.equals("https://dashscope-intl.aliyuncs.com/api/v1")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only official DashScope China or international endpoints are allowed."
            );
        }

        settingsService.saveSettings(
                trimToEmpty(payload.getDashscopeApiKey()),
                baseUrl,
                trimToEmpty(payload.getChatModelName())
        );
        return Map.of("ok", true);
    }

    @GetMapping("/models")
    public Mono<Map<String, Object>> listModels(
            @RequestParam(value = "list_region", required = false) String listRegion) {
        String apiKey = settingsService.getApiKey();
        String baseUrl = settingsService.getBaseUrl();

        boolean environmentIsInternational = baseUrl.contains("intl");
        boolean listIsInternational;
        if ("intl".equals(listRegion)) {
            listIsInternational = true;
        } else if ("china".equals(listRegion)) {
            listIsInternational = false;
        } else {
            listIsInternational = environmentIsInternational;
        }

        List<String> models = listIsInternational ? INTL_MODELS : CHINA_MODELS;
        if (apiKey.isEmpty()) {
            Map<String, Object> response = new HashMap<>();
            response.put("models", models);
            response.put("key_valid", false);
            response.put("key_error", "API Key 未配置。");
            response.put("region", listIsInternational ? "intl" : "china");
            return Mono.just(response);
        }

        String compatibleBase = environmentIsInternational
                ? "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
                : "https://dashscope.aliyuncs.com/compatible-mode/v1";
        String validationUrl = compatibleBase + "/chat/completions";

        Map<String, Object> body = new HashMap<>();
        body.put("model", "qwen-turbo");
        body.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        body.put("max_tokens", 1);

        log.info("Validating API Key at endpoint: {}", validationUrl);
        boolean finalListIsInternational = listIsInternational;
        return webClient.post()
                .uri(validationUrl)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(response -> {
                    Map<String, Object> result = modelResponse(models, finalListIsInternational);
                    int status = response.statusCode().value();
                    if (status == 200 || status == 400) {
                        result.put("key_valid", true);
                        result.put("key_error", "");
                    } else if (status == 401) {
                        result.put("key_valid", false);
                        result.put("key_error", "API Key 无效，请检查是否填写正确。");
                    } else {
                        result.put("key_valid", null);
                        result.put("key_error", "验证请求返回 " + status + "，无法确认。");
                    }
                    return Mono.just(result);
                })
                .onErrorResume(error -> {
                    Map<String, Object> result = modelResponse(models, finalListIsInternational);
                    result.put("key_valid", null);
                    result.put("key_error", "网络请求失败：" + error.getMessage());
                    return Mono.just(result);
                });
    }

    @GetMapping("/config/status")
    public Map<String, Boolean> configStatus() {
        return Map.of("dashscope_configured", settingsService.isDashScopeConfigured());
    }

    private Map<String, Object> modelResponse(List<String> models, boolean international) {
        Map<String, Object> response = new HashMap<>();
        response.put("models", models);
        response.put("region", international ? "intl" : "china");
        return response;
    }

    private String maskApiKey(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        String visible = key.length() >= 4 ? key.substring(key.length() - 4) : key;
        return "sk-****" + visible;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
