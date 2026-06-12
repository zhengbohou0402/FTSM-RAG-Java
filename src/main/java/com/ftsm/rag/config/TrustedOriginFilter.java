package com.ftsm.rag.config;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TrustedOriginFilter implements WebFilter {

    private static final Set<String> TAURI_ORIGINS = Set.of(
            "http://tauri.localhost",
            "https://tauri.localhost",
            "tauri://localhost"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String origin = exchange.getRequest().getHeaders().getOrigin();
        if (path.startsWith("/api/") && origin != null && !isTrustedOrigin(origin)) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    static boolean isTrustedOrigin(String origin) {
        if (TAURI_ORIGINS.contains(origin)) {
            return true;
        }
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
