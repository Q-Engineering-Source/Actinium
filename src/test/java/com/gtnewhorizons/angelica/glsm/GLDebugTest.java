package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.backend.GLDebugMessageListener;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GLDebugTest {
    @Test
    void acceptsBothInstalledCallbackResults() {
        assertDoesNotThrow(() -> GLDebug.requireInstalledCallback(1, "test backend"));
        assertDoesNotThrow(() -> GLDebug.requireInstalledCallback(2, "test backend"));
    }

    @Test
    void rejectsMissingOrUnknownCallbackResults() {
        IllegalStateException missing = assertThrows(
            IllegalStateException.class,
            () -> GLDebug.requireInstalledCallback(0, "test backend")
        );
        assertTrue(missing.getMessage().contains("test backend"));

        // 3 = the backend has no GL debug channel (e.g. SDL GPU); accepted, not an error
        assertDoesNotThrow(() -> GLDebug.requireInstalledCallback(3, "test backend"));

        assertThrows(
            IllegalStateException.class,
            () -> GLDebug.requireInstalledCallback(4, "test backend")
        );
    }

    @Test
    void nullDebugCallbackAdaptsToNull() {
        assertNull(GLDebug.adaptDebugCallback(null));
    }

    /**
     * The adapter resolves the runtime callback class's public
     * {@code invoke(int,int,int,int,int,long,long)} SAM reflectively; a compatible callback must
     * receive every delivered argument.
     */
    @Test
    void callbackWithInvokeSignatureReceivesDeliveredMessage() {
        InvokeShapedCallback callback = new InvokeShapedCallback();
        GLDebugMessageListener listener = GLDebug.adaptDebugCallback(callback);

        listener.onMessage(1, 2, 3, 4, 5, 6L, 7L);

        assertEquals(1, callback.source);
        assertEquals(2, callback.type);
        assertEquals(3, callback.id);
        assertEquals(4, callback.severity);
        assertEquals(5, callback.length);
        assertEquals(6L, callback.message);
        assertEquals(7L, callback.userParam);
    }

    /** Stand-in for the lwjgl3-style KHRDebugCallback SAM shape the adapter looks up. */
    public static class InvokeShapedCallback {
        int source;
        int type;
        int id;
        int severity;
        int length;
        long message;
        long userParam;

        public void invoke(int source, int type, int id, int severity, int length, long message, long userParam) {
            this.source = source;
            this.type = type;
            this.id = id;
            this.severity = severity;
            this.length = length;
            this.message = message;
            this.userParam = userParam;
        }
    }
}
