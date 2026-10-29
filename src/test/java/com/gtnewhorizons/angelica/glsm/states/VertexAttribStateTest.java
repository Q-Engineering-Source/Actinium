package com.gtnewhorizons.angelica.glsm.states;

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
    private VertexAttribState state;

    // Must be a direct (native) buffer: the instance model captures the client pointer's
    // native address via MemoryUtilities, which has no valid address for a heap buffer.
    private static final ByteBuffer VERTEX_DATA = ByteBuffer.allocateDirect(256);

    @BeforeEach
    void resetState() {
        state = new VertexAttribState(405L, new BufferGenerationTracker(405L));
        state.initForContext(0);
    }

    private void enableClientArray(Usage usage) {
        final int location = usage.getAttributeLocation();
        state.setPointer(location, 3, GL11.GL_FLOAT, false, 12, VERTEX_DATA, 0);
        state.setEnabledForContext(location, true);
    }

    /**
     * A reset during startup runs while another thread may already have issued client-array calls, so the
     * model has to stay usable for the VAO that is still bound (#216).
     */
    @Test
    void resetStateKeepsCurrentVaoReadyForAttributeUpdates() {
        final int location = Usage.POSITION.getAttributeLocation();
        state.bindVertexArray(7);

        state.resetState();
        state.setEnabledForContext(location, true);

        assertTrue(state.isAttribEnabledForContext(location));
        assertFalse(state.hasAnyClientSideEnabledAttribForContext());
    }

    @Test
    void positionOnlyDrawYieldsNoFormatFlags() {
        enableClientArray(Usage.POSITION);

        assertTrue(state.hasAnyClientSideEnabledAttribForContext());
        assertEquals(0, state.currentClientArrayVertexFlagsForContext());
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
            state.currentClientArrayVertexFlagsForContext());
    }

    @Test
    void disabledAttributeContributesNothing() {
        enableClientArray(Usage.POSITION);
        enableClientArray(Usage.COLOR);
        state.setEnabledForContext(Usage.COLOR.getAttributeLocation(), false);

        assertEquals(0, state.currentClientArrayVertexFlagsForContext());
    }

    @Test
    void vboBackedAttributeStillCountsAsProvidedData() {
        enableClientArray(Usage.POSITION);
        final int colorLocation = Usage.COLOR.getAttributeLocation();
        state.setPointer(colorLocation, 4, GL11.GL_UNSIGNED_BYTE, true, 16, 0L, 7);
        state.setEnabledForContext(colorLocation, true);

        assertEquals(VertexFlags.COLOR_BIT, state.currentClientArrayVertexFlagsForContext());
    }

    @Test
    void clientSideDetectionRequiresAClientPointer() {
        enableClientArray(Usage.POSITION);
        assertTrue(state.hasAnyClientSideEnabledAttribForContext());

        state.setEnabledForContext(Usage.POSITION.getAttributeLocation(), false);
        assertFalse(state.hasAnyClientSideEnabledAttribForContext());

        enableClientArray(Usage.POSITION);
        state.setPointer(Usage.POSITION.getAttributeLocation(), 3, GL11.GL_FLOAT, false, 12, 0L, 3);
        assertFalse(state.hasAnyClientSideEnabledAttribForContext());
    }
}
