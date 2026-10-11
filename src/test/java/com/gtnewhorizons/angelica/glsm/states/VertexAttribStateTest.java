package com.gtnewhorizons.angelica.glsm.states;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.streaming.StubRenderBackend;
import com.gtnewhorizons.angelica.glsm.testutil.Reflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the vertex-format flags derived for draws fed by client-side arrays
 * (the legacy immediate-mode path, e.g. the vanilla end portal TESR). Those draws must
 * follow the actually enabled attributes rather than any cached format-based flag set, or
 * the FFP shader reads attributes the draw does not provide (a POSITION-only draw misread
 * as having vertex color renders with the black default attribute value).
 */
class VertexAttribStateTest {

    /** Answers only the query GLStateManager makes while it initializes; the tests must not touch GL. */
    private static final class InitBackend extends StubRenderBackend {
        @Override public int getInteger(int pname) { return 32; }
    }

    private RenderBackend original;

    // The install has to happen before anything touches GLStateManager: its clinit queries the backend.

    @AfterEach
    void restoreBackend() {
        if (original != null) Reflect.setStaticFinal(BackendManager.class, "RENDER_BACKEND", RenderBackend.class, original);
        original = null;
    }

    // Must be a direct (native) buffer: VertexAttribState.set captures the client pointer's
    // native address via MemoryUtilities, which has no valid address for a heap buffer.
    private static final ByteBuffer VERTEX_DATA = ByteBuffer.allocateDirect(256);

    @BeforeEach
    void resetState() {
        original = BackendManager.RENDER_BACKEND;
        Reflect.setStaticFinal(BackendManager.class, "RENDER_BACKEND", RenderBackend.class, new InitBackend());
        GLStateManager.ctx().vao.init(0);
        }

    private static void enableClientArray(Usage usage) {
        final int location = usage.getAttributeLocation();
        GLStateManager.ctx().vao.set(location, 3, GL11.GL_FLOAT, false, 12, VERTEX_DATA, 0);
        GLStateManager.ctx().vao.setEnabled(location, true);
    }

    @Test
    void positionOnlyDrawYieldsNoFormatFlags() {
        enableClientArray(Usage.POSITION);

        assertTrue(GLStateManager.ctx().vao.hasAnyClientSideEnabledAttrib());
        assertEquals(0, GLStateManager.ctx().vao.currentClientArrayVertexFlags());
    }

    @Test
    void enabledAttributesContributeTheirFlag() {
        enableClientArray(Usage.POSITION);
        enableClientArray(Usage.COLOR);
        enableClientArray(Usage.NORMAL);
        enableClientArray(Usage.PRIMARY_UV);
        enableClientArray(Usage.SECONDARY_UV);

        assertEquals(
            VertexFlags.COLOR_BIT | VertexFlags.NORMAL_BIT | VertexFlags.TEXTURE_BIT | VertexFlags.BRIGHTNESS_BIT,
            GLStateManager.ctx().vao.currentClientArrayVertexFlags());
    }

    @Test
    void disabledAttributeContributesNothing() {
        enableClientArray(Usage.POSITION);
        enableClientArray(Usage.COLOR);
        GLStateManager.ctx().vao.setEnabled(Usage.COLOR.getAttributeLocation(), false);

        assertEquals(0, GLStateManager.ctx().vao.currentClientArrayVertexFlags());
    }

    @Test
    void vboBackedAttributeStillCountsAsProvidedData() {
        enableClientArray(Usage.POSITION);
        final int colorLocation = Usage.COLOR.getAttributeLocation();
        GLStateManager.ctx().vao.set(colorLocation, 4, GL11.GL_UNSIGNED_BYTE, true, 16, 0L, 7);
        GLStateManager.ctx().vao.setEnabled(colorLocation, true);

        assertEquals(VertexFlags.COLOR_BIT, GLStateManager.ctx().vao.currentClientArrayVertexFlags());
    }

    @Test
    void clientSideDetectionRequiresAClientPointer() {
        enableClientArray(Usage.POSITION);
        assertTrue(GLStateManager.ctx().vao.hasAnyClientSideEnabledAttrib());

        GLStateManager.ctx().vao.setEnabled(Usage.POSITION.getAttributeLocation(), false);
        assertFalse(GLStateManager.ctx().vao.hasAnyClientSideEnabledAttrib());

        enableClientArray(Usage.POSITION);
        GLStateManager.ctx().vao.set(Usage.POSITION.getAttributeLocation(), 3, GL11.GL_FLOAT, false, 12, 0L, 3);
        assertFalse(GLStateManager.ctx().vao.hasAnyClientSideEnabledAttrib());
    }
}
