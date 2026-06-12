package com.ftsm.rag.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopLifecycleControllerTest {

    @Test
    void shutdownTokenMustMatchExactly() {
        assertTrue(DesktopLifecycleController.tokensMatch("secret-token", "secret-token"));
        assertFalse(DesktopLifecycleController.tokensMatch("secret-token", "secret-token-extra"));
        assertFalse(DesktopLifecycleController.tokensMatch("secret-token", null));
    }
}
