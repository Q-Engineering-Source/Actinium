package com.gtnewhorizons.angelica.glsm.ffp;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VAOManagerTest {
    private static final ByteBuffer VERTEX_DATA = ByteBuffer.allocateDirect(256);

    private final VAOManager vaos = new VAOManager();

    @BeforeEach
    void resetState() {
        vaos.init(0);
    }

    @Test
    void resetKeepsCurrentVaoReadyForAttributeUpdates() {
        final int location = Usage.POSITION.getAttributeLocation();
        vaos.onBindVertexArrayPre(7);

        vaos.reset(7);
        vaos.setEnabled(location, true);

        assertTrue(vaos.isAttribEnabled(location));
        assertFalse(vaos.hasAnyClientSideEnabledAttrib());
    }

    private void enableClientArray(Usage usage) {
        final int location = usage.getAttributeLocation();
        vaos.setAttribute(location, 3, GL11.GL_FLOAT, false, 12, VERTEX_DATA, 0);
        vaos.setEnabled(location, true);
    }

    @Test
    void positionOnlyDrawYieldsNoFormatFlags() {
        enableClientArray(Usage.POSITION);

        assertTrue(vaos.hasAnyClientSideEnabledAttrib());
        assertEquals(0, vaos.currentClientArrayVertexFlags());
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
            vaos.currentClientArrayVertexFlags());
    }

    @Test
    void disabledAttributeContributesNothing() {
        enableClientArray(Usage.POSITION);
        enableClientArray(Usage.COLOR);
        vaos.setEnabled(Usage.COLOR.getAttributeLocation(), false);

        assertEquals(0, vaos.currentClientArrayVertexFlags());
    }

    @Test
    void vboBackedAttributeStillCountsAsProvidedData() {
        enableClientArray(Usage.POSITION);
        final int colorLocation = Usage.COLOR.getAttributeLocation();
        vaos.setAttribute(colorLocation, 4, GL11.GL_UNSIGNED_BYTE, true, 16, 0L, 7);
        vaos.setEnabled(colorLocation, true);

        assertEquals(VertexFlags.COLOR_BIT, vaos.currentClientArrayVertexFlags());
    }

    @Test
    void clientSideDetectionRequiresAClientPointer() {
        enableClientArray(Usage.POSITION);
        assertTrue(vaos.hasAnyClientSideEnabledAttrib());

        vaos.setEnabled(Usage.POSITION.getAttributeLocation(), false);
        assertFalse(vaos.hasAnyClientSideEnabledAttrib());

        enableClientArray(Usage.POSITION);
        vaos.setAttribute(Usage.POSITION.getAttributeLocation(), 3, GL11.GL_FLOAT, false, 12, 0L, 3);
        assertFalse(vaos.hasAnyClientSideEnabledAttrib());
    }
}
