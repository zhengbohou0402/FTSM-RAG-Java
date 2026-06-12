package com.ftsm.rag.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedOriginFilterTest {

    @Test
    void acceptsOnlyLocalBrowserAndTauriOrigins() {
        assertTrue(TrustedOriginFilter.isTrustedOrigin("http://127.0.0.1:5173"));
        assertTrue(TrustedOriginFilter.isTrustedOrigin("http://localhost:8000"));
        assertTrue(TrustedOriginFilter.isTrustedOrigin("tauri://localhost"));
        assertFalse(TrustedOriginFilter.isTrustedOrigin("https://example.test"));
        assertFalse(TrustedOriginFilter.isTrustedOrigin("not a uri"));
    }
}
