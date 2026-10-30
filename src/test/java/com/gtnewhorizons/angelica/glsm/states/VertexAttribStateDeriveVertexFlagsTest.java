package com.gtnewhorizons.angelica.glsm.states;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement.Usage;
import org.lwjgl.opengl.GL11;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that {@link VertexAttribState#currentClientArrayVertexFlags()} derives the FFP shader
 * vertex-flag mask from the currently bound VAO's enabled attributes.
 *
 * <p>The FFP shader pipeline uses this mask for raw GL draw entry points
 * ({@code ShaderManager.preDraw()}) and when binding a VAO never seen through the vanilla
 * {@code VertexFormat} setup path. The derived mask must reflect exactly the attributes the
 * VAO provides — in particular it must not leak {@code COLOR_BIT}/{@code NORMAL_BIT} that a
 * third-party client-state call (e.g. HBM-CE's {@code glEnableClientState(GL_COLOR_ARRAY)}
 * during model upload) left behind in the global flags without a matching VAO attribute;
 * a shader that declares an attribute the VAO does not provide reads the default
 * {@code (0,0,0,1)} and renders black.</p>
 */
class VertexAttribStateDeriveVertexFlagsTest {
    @BeforeEach
    void setUp() {
        VertexAttribState.init(0);
    }

    @AfterEach
    void tearDown() {
        VertexAttribState.reset();
    }

    /**
     * Motivation: a freshly created VAO (or the default VAO before the first vanilla draw)
     * has no attributes enabled; the derived mask must be empty rather than inherit leaked
     * flags from an earlier draw.
     */
    @Test
    void freshVAOderivesNoFlags() {
        VertexAttribState.onBindVertexArray(1);
        assertEquals(0, VertexAttribState.currentClientArrayVertexFlags());
    }

    /**
     * Motivation: an OBJ model uploaded without color (HBM-CE's non-colored models) enables
     * only position/texture/normal arrays; the derived mask must not include COLOR_BIT even
     * if a previous colored model upload leaked it into the global client flags.
     */
    @Test
    void noColorAttribDerivesNoColorBit() {
        VertexAttribState.onBindVertexArray(1);
        setEnabled(Usage.POSITION.getAttributeLocation(), true);
        setEnabled(Usage.PRIMARY_UV.getAttributeLocation(), true);
        setEnabled(Usage.NORMAL.getAttributeLocation(), true);

        int flags = VertexAttribState.currentClientArrayVertexFlags();
        assertEquals(VertexFlags.TEXTURE_BIT | VertexFlags.NORMAL_BIT, flags,
            "mask must not include COLOR_BIT when the VAO has no color attribute");
    }

    /**
     * Motivation: a colored model upload enables the color array too; the derived mask must
     * then include COLOR_BIT so the shader declares a_Color and reads the real data.
     */
    @Test
    void colorAttribDerivesColorBit() {
        VertexAttribState.onBindVertexArray(1);
        setEnabled(Usage.POSITION.getAttributeLocation(), true);
        setEnabled(Usage.PRIMARY_UV.getAttributeLocation(), true);
        setEnabled(Usage.COLOR.getAttributeLocation(), true);

        int flags = VertexAttribState.currentClientArrayVertexFlags();
        assertEquals(VertexFlags.TEXTURE_BIT | VertexFlags.COLOR_BIT, flags,
            "mask must include COLOR_BIT when the VAO has a color attribute");
    }

    /**
     * Motivation: lightmap (secondary UV) arrays must map to the brightness bit, and the
     * derived mask is per-VAO — binding another VAO without those attributes must drop them
     * again.
     */
    @Test
    void derivedMaskIsPerVao() {
        VertexAttribState.onBindVertexArray(1);
        setEnabled(Usage.POSITION.getAttributeLocation(), true);
        setEnabled(Usage.SECONDARY_UV.getAttributeLocation(), true);
        assertEquals(VertexFlags.BRIGHTNESS_BIT, VertexAttribState.currentClientArrayVertexFlags());

        VertexAttribState.onBindVertexArray(2);
        assertEquals(0, VertexAttribState.currentClientArrayVertexFlags(),
            "binding a different VAO must not keep the previous VAO's brightness bit");
    }

    /**
     * Motivation: attributes can be toggled after binding; the derived mask must follow the
     * current enablement, so a leaked color enable that is later disabled disappears from the
     * mask.
     */
    @Test
    void disablingAttribDropsItsBit() {
        VertexAttribState.onBindVertexArray(1);
        setEnabled(Usage.COLOR.getAttributeLocation(), true);
        assertEquals(VertexFlags.COLOR_BIT, VertexAttribState.currentClientArrayVertexFlags());

        setEnabled(Usage.COLOR.getAttributeLocation(), false);
        assertEquals(0, VertexAttribState.currentClientArrayVertexFlags());
    }

    /**
     * Motivation: HBM's client-memory arrays can pass a view whose position skips a prefix;
     * recorder reads must treat that position as the pointer base and retain the vertex stride.
     */
    @Test
    void clientPointerReadsFromCurrentPositionAcrossVertexStride() {
        final int position = 8;
        final int stride = 16;
        final ByteBuffer source = ByteBuffer.allocateDirect(position + stride * 2).order(ByteOrder.nativeOrder());
        source.putFloat(0, -100.0f);
        source.putFloat(position, 10.0f);
        source.putFloat(position + 4, 11.0f);
        source.putFloat(position + 8, 12.0f);
        source.putFloat(position + stride, 20.0f);
        source.putFloat(position + stride + 4, 21.0f);
        source.putFloat(position + stride + 8, 22.0f);
        source.limit(position + 12);
        source.position(position);

        final int location = Usage.POSITION.getAttributeLocation();
        VertexAttribState.set(location, 3, GL11.GL_FLOAT, false, stride, source, 0);
        final VertexAttribState.Attrib attrib = VertexAttribState.get(location);

        assertEquals(position, source.position());
        assertEquals(0, attrib.clientPointer.position());
        assertEquals(source.capacity() - position, attrib.clientPointer.capacity());
        assertEquals(ByteOrder.nativeOrder(), attrib.clientPointer.order());
        assertEquals(10.0f, attrib.readComponent(attrib.clientPointer, 0, 0));
        assertEquals(12.0f, attrib.readComponent(attrib.clientPointer, 0, 2));
        assertEquals(20.0f, attrib.readComponent(attrib.clientPointer, stride, 0));
        assertEquals(22.0f, attrib.readComponent(attrib.clientPointer, stride, 2));
    }

    private static void setEnabled(int index, boolean enabled) {
        VertexAttribState.setEnabled(index, enabled);
    }
}
