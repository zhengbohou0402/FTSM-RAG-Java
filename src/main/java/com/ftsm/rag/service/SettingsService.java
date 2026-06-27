package com.ftsm.rag.service;

import com.ftsm.rag.config.AppConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SettingsService {

    private final AppConfig appConfig;
    private final Path envPath;
    private final Map<String, String> runtimeEnv = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();

    @Autowired
    public SettingsService(AppConfig appConfig) {
        this(appConfig, resolveEnvPath());
    }

    SettingsService(AppConfig appConfig, Path envPath) {
        this.appConfig = appConfig;
        this.envPath = envPath.toAbsolutePath().normalize();
        loadEnv();
    }

    private static Path resolveEnvPath() {
        String projectRoot = System.getenv("FTSM_PROJECT_ROOT");
        if (projectRoot != null && !projectRoot.trim().isEmpty()) {
            return Paths.get(projectRoot, ".env");
        }
        return Paths.get(".env");
    }

    public synchronized void registerListener(Runnable listener) {
        listeners.add(listener);
    }

    private synchronized void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Exception e) {
                log.error("Failed to notify settings listener", e);
            }
        }
    }

    private void loadEnv() {
        if (!Files.exists(envPath)) {
            Path example = envPath.resolveSibling(".env.example");
            if (!Files.exists(example)) {
                example = Paths.get(".env.example").toAbsolutePath().normalize();
            }
            if (Files.exists(example)) {
                try {
                    Files.createDirectories(envPath.getParent());
                    Files.copy(example, envPath);
                    log.info("Copied .env.example to .env");
                } catch (IOException e) {
                    log.warn("Failed to copy .env.example: {}", e.getMessage());
                }
            } else {
                try {
                    Files.createDirectories(envPath.getParent());
                    Files.createFile(envPath);
                } catch (IOException e) {
                    log.warn("Failed to create .env file: {}", e.getMessage());
                }
            }
        }

        if (Files.exists(envPath)) {
            try {
                List<String> lines = Files.readAllLines(envPath, StandardCharsets.UTF_8);
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                        continue;
                    }
                    int eqIdx = line.indexOf('=');
                    String key = line.substring(0, eqIdx).trim();
                    String value = line.substring(eqIdx + 1).trim();
                    runtimeEnv.put(key, value);
                    // Also set as system property so Spring properties resolver can find them
                    System.setProperty(key, value);
                }
                log.info("Loaded {} variables from .env file", runtimeEnv.size());
            } catch (IOException e) {
                log.error("Failed to read .env file", e);
            }
        }
    }

    public String getApiKey() {
        String key = runtimeEnv.get("DASHSCOPE_API_KEY");
        if (key == null || key.trim().isEmpty()) {
            key = System.getenv("DASHSCOPE_API_KEY");
        }
        return key != null ? key.trim() : "";
    }

    public String getBaseUrl() {
        String url = runtimeEnv.get("DASHSCOPE_BASE_URL");
        if (url == null || url.trim().isEmpty()) {
            url = System.getenv("DASHSCOPE_BASE_URL");
        }
        return url != null ? url.trim() : appConfig.getDashscope().getBaseUrl();
    }

    public String getChatModel() {
        String model = runtimeEnv.get("CHAT_MODEL_NAME");
        if (model == null || model.trim().isEmpty()) {
            model = System.getenv("CHAT_MODEL_NAME");
        }
        return model != null ? model.trim() : appConfig.getDashscope().getChatModel();
    }

    public String getLlmProvider() {
        String provider = runtimeEnv.get("LLM_PROVIDER");
        if (provider == null || provider.trim().isEmpty()) {
            provider = System.getenv("LLM_PROVIDER");
        }
        return provider != null ? provider.trim() : appConfig.getLlmProvider();
    }

    public String getVertexProjectId() {
        String id = runtimeEnv.get("VERTEX_PROJECT_ID");
        if (id == null || id.trim().isEmpty()) {
            id = System.getenv("VERTEX_PROJECT_ID");
        }
        return id != null ? id.trim() : appConfig.getVertexai().getProjectId();
    }

    public String getVertexLocation() {
        String loc = runtimeEnv.get("VERTEX_LOCATION");
        if (loc == null || loc.trim().isEmpty()) {
            loc = System.getenv("VERTEX_LOCATION");
        }
        return loc != null ? loc.trim() : appConfig.getVertexai().getLocation();
    }

    public String getVertexModelName() {
        String name = runtimeEnv.get("VERTEX_MODEL_NAME");
        if (name == null || name.trim().isEmpty()) {
            name = System.getenv("VERTEX_MODEL_NAME");
        }
        return name != null ? name.trim() : appConfig.getVertexai().getModelName();
    }

    public String getVertexEmbeddingModelName() {
        String name = runtimeEnv.get("VERTEX_EMBEDDING_MODEL_NAME");
        if (name == null || name.trim().isEmpty()) {
            name = System.getenv("VERTEX_EMBEDDING_MODEL_NAME");
        }
        return name != null ? name.trim() : appConfig.getVertexai().getEmbeddingModel();
    }

    public String getVertexCredentialsPath() {
        String path = runtimeEnv.get("VERTEX_CREDENTIALS_PATH");
        if (path == null || path.trim().isEmpty()) {
            path = System.getenv("VERTEX_CREDENTIALS_PATH");
        }
        return path != null ? path.trim() : appConfig.getVertexai().getCredentialsPath();
    }

    public boolean isDashScopeConfigured() {
        String key = getApiKey();
        return key != null && !key.trim().isEmpty();
    }

    public boolean isLlmConfigured() {
        String provider = getLlmProvider();
        if ("vertexai".equalsIgnoreCase(provider)) {
            String projectId = getVertexProjectId();
            return projectId != null && !projectId.trim().isEmpty();
        } else {
            return isDashScopeConfigured();
        }
    }

    public synchronized void saveSettings(String apiKey, String baseUrl, String chatModelName) {
        Map<String, String> updates = new HashMap<>();
        
        // If frontend passes masked key, do not overwrite
        if (apiKey != null && !apiKey.trim().isEmpty() && !apiKey.startsWith("sk-****")) {
            updates.put("DASHSCOPE_API_KEY", apiKey.trim());
        }
        
        if (baseUrl != null) {
            updates.put("DASHSCOPE_BASE_URL", baseUrl.trim());
        }
        
        if (chatModelName != null) {
            updates.put("CHAT_MODEL_NAME", chatModelName.trim());
        }

        try {
            List<String> lines = new ArrayList<>();
            Set<String> written = new HashSet<>();

            if (Files.exists(envPath)) {
                List<String> existingLines = Files.readAllLines(envPath, StandardCharsets.UTF_8);
                for (String line : existingLines) {
                    String stripped = line.trim();
                    if (!stripped.isEmpty() && !stripped.startsWith("#") && stripped.contains("=")) {
                        int eqIdx = stripped.indexOf('=');
                        String key = stripped.substring(0, eqIdx).trim();
                        if (updates.containsKey(key)) {
                            lines.add(key + "=" + updates.get(key));
                            written.add(key);
                            continue;
                        }
                    }
                    lines.add(line);
                }
            }

            for (Map.Entry<String, String> entry : updates.entrySet()) {
                if (!written.contains(entry.getKey())) {
                    lines.add(entry.getKey() + "=" + entry.getValue());
                }
            }

            writeEnvAtomically(lines);
            updates.forEach((key, value) -> {
                runtimeEnv.put(key, value);
                System.setProperty(key, value);
            });
            log.info("Saved settings to .env file");
        } catch (IOException e) {
            log.error("Failed to write .env file", e);
            throw new IllegalStateException("Failed to save settings", e);
        }

        notifyListeners();
    }

    private void writeEnvAtomically(List<String> lines) throws IOException {
        Files.createDirectories(envPath.getParent());
        Path tempPath = Files.createTempFile(envPath.getParent(), ".env-", ".tmp");
        try {
            Files.write(tempPath, lines, StandardCharsets.UTF_8);
            try {
                Files.move(
                        tempPath,
                        envPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (IOException atomicMoveError) {
                Files.move(tempPath, envPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempPath);
        }
    }
}
