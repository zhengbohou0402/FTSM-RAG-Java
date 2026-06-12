package com.ftsm.rag.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequestMapping("/api/desktop")
public class DesktopLifecycleController {

    private final ConfigurableApplicationContext applicationContext;
    private final String shutdownToken;

    public DesktopLifecycleController(
            ConfigurableApplicationContext applicationContext,
            @Value("${FTSM_DESKTOP_SHUTDOWN_TOKEN:}") String shutdownToken) {
        this.applicationContext = applicationContext;
        this.shutdownToken = shutdownToken;
    }

    @PostMapping("/shutdown")
    public Map<String, Boolean> shutdown(
            @RequestHeader(value = "X-FTSM-Shutdown-Token", required = false) String suppliedToken) {
        if (shutdownToken.isBlank() || !tokensMatch(shutdownToken, suppliedToken)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Desktop shutdown is not authorized");
        }

        Thread shutdownThread = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            applicationContext.close();
        }, "ftsm-desktop-shutdown");
        shutdownThread.setDaemon(false);
        shutdownThread.start();
        return Map.of("ok", true);
    }

    static boolean tokensMatch(String expected, String supplied) {
        if (expected == null || supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8)
        );
    }
}
