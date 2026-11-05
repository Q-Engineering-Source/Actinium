package com.dhj.actinium.startup;

import net.minecraftforge.common.ForgeEarlyConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeEarlyGlConfigTest {
    @Test
    void reportsTheCleanroomEarlyOpenGlFields() {
        assertTrue(ForgeEarlyGlConfig.available(), "The Cleanroom classpath exposes the ForgeEarlyConfig fields");
    }

    @Test
    void appliesTheCoreProfileRequest() {
        ForgeEarlyGlConfig request = ForgeEarlyGlConfig.capture();

        try {
            request.apply(3, 3, true);

            assertEquals(3, ForgeEarlyConfig.OPENGL_VERSION_MAJOR);
            assertEquals(3, ForgeEarlyConfig.OPENGL_VERSION_MINOR);
            assertFalse(ForgeEarlyConfig.OPENGL_COMPAT_PROFILE);
            assertTrue(ForgeEarlyConfig.OPENGL_DEBUG_CONTEXT);
        } finally {
            request.restore();
        }
    }

    @Test
    void restoresTheCapturedSettingsAfterTheRequest() {
        ForgeEarlyConfig.OPENGL_VERSION_MAJOR = 4;
        ForgeEarlyConfig.OPENGL_VERSION_MINOR = 5;
        ForgeEarlyConfig.OPENGL_COMPAT_PROFILE = true;
        ForgeEarlyConfig.OPENGL_DEBUG_CONTEXT = false;
        ForgeEarlyGlConfig request = ForgeEarlyGlConfig.capture();

        request.apply(4, 6, true);
        request.restore();

        assertEquals(4, ForgeEarlyConfig.OPENGL_VERSION_MAJOR);
        assertEquals(5, ForgeEarlyConfig.OPENGL_VERSION_MINOR);
        assertTrue(ForgeEarlyConfig.OPENGL_COMPAT_PROFILE);
        assertFalse(ForgeEarlyConfig.OPENGL_DEBUG_CONTEXT);
    }
}
