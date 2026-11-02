package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Drives the pack and unpack pixel store families against a real context: every pname round-trips through
 * the tracked state of its own family, an illegal value leaves the tracked state alone while still
 * reaching the driver, and {@code glPushClientAttrib(GL_CLIENT_PIXEL_STORE_BIT)} restores both families
 * on the driver as well.
 */
class PixelStoreStateIntegrationTest extends GLStateManagerIntegrationFixture {

    @BeforeEach
    void bindDisplay() {
        WindowBackends.useSdl();
        GLStateManager.bindDisplayContext(new Object(), context);
        GLStateManager.recreateDefaultVertexArray();
        drainDriverErrors();
    }

    @AfterEach
    void restoreDefaults() {
        GLStateManager.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GLStateManager.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GLStateManager.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GLStateManager.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
        GLStateManager.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GLStateManager.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
        GLStateManager.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GLStateManager.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
        GLStateManager.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GLStateManager.glPixelStorei(GL12.GL_PACK_IMAGE_HEIGHT, 0);
        GLStateManager.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        GLStateManager.glPixelStorei(GL12.GL_PACK_SKIP_IMAGES, 0);
        GLStateManager.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES, 0);
        GLStateManager.glPixelStorei(GL11.GL_PACK_SWAP_BYTES, 0);
        GLStateManager.glPixelStorei(GL11.GL_UNPACK_LSB_FIRST, 0);
        GLStateManager.glPixelStorei(GL11.GL_PACK_LSB_FIRST, 0);
        while (GLStateManager.glGetInteger(GL11.GL_CLIENT_ATTRIB_STACK_DEPTH) > 0) {
            GLStateManager.glPopClientAttrib();
        }
        drainDriverErrors();
    }

    @Test
    void eachFamilyAnswersOnlyItsOwnPnames() {
        GLStateManager.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 8);
        GLStateManager.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GLStateManager.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 32);
        GLStateManager.glPixelStorei(GL12.GL_PACK_SKIP_IMAGES, 3);
        GLStateManager.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 5);

        assertEquals(8, GLStateManager.glGetInteger(GL11.GL_UNPACK_ALIGNMENT));
        assertEquals(1, GLStateManager.glGetInteger(GL11.GL_PACK_ALIGNMENT));
        assertEquals(32, GLStateManager.glGetInteger(GL12.GL_UNPACK_IMAGE_HEIGHT));
        assertEquals(0, GLStateManager.glGetInteger(GL12.GL_PACK_IMAGE_HEIGHT), "the pack family keeps its own image height");
        assertEquals(3, GLStateManager.glGetInteger(GL12.GL_PACK_SKIP_IMAGES));
        assertEquals(0, GLStateManager.glGetInteger(GL12.GL_UNPACK_SKIP_IMAGES), "the pack family keeps its own skip images");
        assertEquals(5, GLStateManager.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS));
        assertEquals(0, GLStateManager.glGetInteger(GL11.GL_PACK_SKIP_PIXELS));

        assertEquals(8, driverInteger(GL11.GL_UNPACK_ALIGNMENT), "the unpack value must reach the driver");
        assertEquals(1, driverInteger(GL11.GL_PACK_ALIGNMENT), "the pack value must reach the driver");
        assertEquals(32, driverInteger(GL12.GL_UNPACK_IMAGE_HEIGHT));
        assertEquals(3, driverInteger(GL12.GL_PACK_SKIP_IMAGES));
        assertEquals(5, driverInteger(GL11.GL_UNPACK_SKIP_PIXELS));
        assertDriverAcceptedLastCalls();
    }

    @Test
    void swapBytesAndLsbFirstAnswerFromTheTrackedState() {
        GLStateManager.glPixelStorei(GL11.GL_UNPACK_SWAP_BYTES, 1);
        GLStateManager.glPixelStorei(GL11.GL_PACK_LSB_FIRST, 1);

        assertEquals(GL11.GL_TRUE, GLStateManager.glGetInteger(GL11.GL_UNPACK_SWAP_BYTES));
        assertEquals(GL11.GL_FALSE, GLStateManager.glGetInteger(GL11.GL_PACK_SWAP_BYTES));
        assertEquals(GL11.GL_TRUE, GLStateManager.glGetInteger(GL11.GL_PACK_LSB_FIRST));
        assertEquals(GL11.GL_FALSE, GLStateManager.glGetInteger(GL11.GL_UNPACK_LSB_FIRST));
    }

    @Test
    void anIllegalValueKeepsTheTrackedStateAndStillReachesTheDriver() {
        GLStateManager.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 8);

        GLStateManager.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 3);
        assertEquals(8, GLStateManager.glGetInteger(GL11.GL_UNPACK_ALIGNMENT), "an illegal alignment leaves the tracked state alone");
        assertEquals(GL11.GL_INVALID_VALUE, GL11.glGetError(), "the illegal alignment still reaches the driver");

        GLStateManager.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, -1);
        assertEquals(0, GLStateManager.glGetInteger(GL11.GL_PACK_ROW_LENGTH), "a negative row length leaves the tracked state alone");
        assertEquals(GL11.GL_INVALID_VALUE, GL11.glGetError(), "the negative row length still reaches the driver");

        assertEquals(8, driverInteger(GL11.GL_UNPACK_ALIGNMENT), "the driver keeps the last accepted alignment");
        drainDriverErrors();
    }

    @Test
    void clientPixelStoreBitRestoresBothFamilies() {
        GLStateManager.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 8);
        GLStateManager.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 64);
        GLStateManager.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
        try {
            GLStateManager.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GLStateManager.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            assertEquals(1, GLStateManager.glGetInteger(GL11.GL_UNPACK_ALIGNMENT));
            assertEquals(0, GLStateManager.glGetInteger(GL11.GL_PACK_ROW_LENGTH));
            assertEquals(1, driverInteger(GL11.GL_UNPACK_ALIGNMENT));
            assertEquals(0, driverInteger(GL11.GL_PACK_ROW_LENGTH));
        } finally {
            GLStateManager.glPopClientAttrib();
        }

        assertEquals(8, GLStateManager.glGetInteger(GL11.GL_UNPACK_ALIGNMENT));
        assertEquals(64, GLStateManager.glGetInteger(GL11.GL_PACK_ROW_LENGTH));
        assertEquals(8, driverInteger(GL11.GL_UNPACK_ALIGNMENT), "the restore must reach the driver");
        assertEquals(64, driverInteger(GL11.GL_PACK_ROW_LENGTH), "the restore must reach the driver");
        assertDriverAcceptedLastCalls();
    }

    @Test
    void pushWithoutThePixelStoreBitLeavesPixelStoreAlone() {
        GLStateManager.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 8);
        GLStateManager.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        try {
            GLStateManager.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        } finally {
            GLStateManager.glPopClientAttrib();
        }

        assertEquals(1, GLStateManager.glGetInteger(GL11.GL_UNPACK_ALIGNMENT), "a push without the pixel store bit does not restore it");
        assertEquals(1, driverInteger(GL11.GL_UNPACK_ALIGNMENT));
        assertDriverAcceptedLastCalls();
    }

    private static int driverInteger(int pname) {
        final IntBuffer driver = BufferUtils.createIntBuffer(1);
        GL11.glGetIntegerv(pname, driver);
        return driver.get(0);
    }

    private static void assertDriverAcceptedLastCalls() {
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "the driver rejected a call GLSM issued");
    }

    private static void drainDriverErrors() {
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
            // Drain stale errors so each assertion only sees the calls made by the test.
        }
    }
}
