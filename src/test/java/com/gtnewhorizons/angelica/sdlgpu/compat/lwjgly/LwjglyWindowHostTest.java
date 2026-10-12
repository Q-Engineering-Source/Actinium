package com.gtnewhorizons.angelica.sdlgpu.compat.lwjgly;

import com.cleanroommc.lwjgly.LWJGLY;
import com.gtnewhorizons.angelica.sdlgpu.SDLGPUGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the SDL window integration of a host running LWJGLY, driven through a real
 * {@code WindowBridge} implementation.
 *
 * <p>What matters here is the host's side of the contract: the window comes from the bridge, its size
 * and resize state are read from the bridge rather than from a drawable, the bridge's absence is
 * reported with the host's obligation, and the SDL gate follows the bridge's presence - LWJGLY is
 * installed, but without a window bridge the backend stays off.</p>
 */
@Timeout(10)
class LwjglyWindowHostTest {

    private static final long WINDOW = 0x0000_7FF6_1234_5678L;

    @AfterEach
    void removeWindowBridge() {
        LWJGLY.setWindowBridge(null);
    }

    @Test
    void reportsTheHostObligationWhileNoBridgeIsInstalled() {
        assertFalse(LwjglyWindowHost.available());
        assertTrue(LwjglyWindowHost.requirement().contains("setWindowBridge"), LwjglyWindowHost.requirement());
        assertTrue(LwjglyWindowHost.requirement().contains("SDL_WINDOW_OPENGL"), LwjglyWindowHost.requirement());
        assertFalse(SDLGPUGate.isWindowSupplierPresent());

        // LWJGLY reports the missing bridge itself, with the same obligation this class documents.
        final IllegalStateException failure =
            assertThrows(IllegalStateException.class, LwjglyWindowHost::window);

        assertTrue(failure.getMessage().contains("setWindowBridge"), failure.getMessage());
    }

    @Test
    void takesTheWindowFromTheBridge() {
        LWJGLY.setWindowBridge(new TestWindowBridge(WINDOW, 1920, 1080));

        assertTrue(LwjglyWindowHost.available());
        assertTrue(SDLGPUGate.isWindowSupplierPresent());
        assertEquals(WINDOW, LwjglyWindowHost.window());
        assertEquals(1920, LwjglyWindowHost.width());
        assertEquals(1080, LwjglyWindowHost.height());
    }

    @Test
    void rejectsABridgeThatReportsNoWindow() {
        LWJGLY.setWindowBridge(new TestWindowBridge(0L, 854, 480));

        final IllegalStateException failure =
            assertThrows(IllegalStateException.class, LwjglyWindowHost::window);

        assertTrue(failure.getMessage().contains("no SDL_Window"), failure.getMessage());
        assertTrue(failure.getMessage().contains("SDL_WINDOW_OPENGL"), failure.getMessage());
    }

    @Test
    void consumesTheResizeNotificationOnce() {
        final TestWindowBridge bridge = new TestWindowBridge(WINDOW, 854, 480);
        LWJGLY.setWindowBridge(bridge);

        assertFalse(LwjglyWindowHost.consumeResized());
        bridge.resize(1280, 720);
        assertTrue(LwjglyWindowHost.consumeResized());
        assertFalse(LwjglyWindowHost.consumeResized());
    }
}
