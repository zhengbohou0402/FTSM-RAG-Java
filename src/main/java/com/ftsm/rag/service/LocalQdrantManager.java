package com.ftsm.rag.service;

import com.ftsm.rag.config.AppConfig;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class LocalQdrantManager {

    private final AppConfig appConfig;
    private Process managedProcess;

    public LocalQdrantManager(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    public synchronized void ensureRunning() {
        AppConfig.QdrantProperties props = appConfig.getQdrant();
        if (isReachable(props.getHost(), props.getPort(), Duration.ofMillis(300))) {
            return;
        }
        if (!props.isAutoStartLocal() || !isLocalHost(props.getHost())) {
            throw new IllegalStateException(
                    "Qdrant is not reachable at " + props.getHost() + ":" + props.getPort());
        }

        Path executable = Paths.get(props.getLocalExecutable()).toAbsolutePath().normalize();
        Path storage = Paths.get(props.getLocalStoragePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(executable)) {
            throw new IllegalStateException("Local Qdrant executable not found: " + executable);
        }

        try {
            Files.createDirectories(storage);
            Path logFile = executable.getParent().resolve("qdrant-java.log");
            ProcessBuilder builder = new ProcessBuilder(executable.toString());
            builder.directory(executable.getParent().toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            builder.environment().put("QDRANT__SERVICE__HOST", "127.0.0.1");
            builder.environment().put("QDRANT__SERVICE__HTTP_PORT", "6333");
            builder.environment().put("QDRANT__SERVICE__GRPC_PORT", String.valueOf(props.getPort()));
            builder.environment().put("QDRANT__STORAGE__STORAGE_PATH", storage.toString());
            managedProcess = builder.start();
            log.info("Started local Qdrant process from {}", executable);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start local Qdrant", e);
        }

        for (int attempt = 0; attempt < 40; attempt++) {
            if (isReachable(props.getHost(), props.getPort(), Duration.ofMillis(250))) {
                return;
            }
            if (managedProcess != null && !managedProcess.isAlive()) {
                throw new IllegalStateException("Local Qdrant exited before becoming ready");
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for Qdrant", e);
            }
        }
        throw new IllegalStateException("Timed out waiting for local Qdrant to start");
    }

    static boolean isLocalHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
    }

    private boolean isReachable(String host, int port, Duration timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @PreDestroy
    public synchronized void stopManagedProcess() {
        if (managedProcess != null && managedProcess.isAlive()) {
            managedProcess.destroy();
            try {
                if (!managedProcess.waitFor(5, TimeUnit.SECONDS)) {
                    managedProcess.destroyForcibly();
                    managedProcess.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                managedProcess.destroyForcibly();
            }
        }
    }
}
