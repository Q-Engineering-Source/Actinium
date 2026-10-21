package com.gtnewhorizons.angelica.glsm.states;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement.Usage;
import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
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
    private VertexAttribState state;

    @BeforeEach
    void setUp() {
        state = new VertexAttribState(404L, new BufferGenerationTracker(404L));
        state.initForContext(0);
    }

    @AfterEach
    void tearDown() {
        state.resetState();
    }

    /**
     * Motivation: a freshly created VAO (or the default VAO before the first vanilla draw)
     * has no attributes enabled; the derived mask must be empty rather than inherit leaked
     * flags from an earlier draw.
     */
    @Test
    void freshVAOderivesNoFlags() {
        state.bindVertexArray(1);
        assertEquals(0, state.currentClientArrayVertexFlagsForContext());
    }

    /**
     * Motivation: an OBJ model uploaded without color (HBM-CE's non-colored models) enables
     * only position/texture/normal arrays; the derived mask must not include COLOR_BIT even
     * if a previous colored model upload leaked it into the global client flags.
     */
    @Test
    void noColorAttribDerivesNoColorBit() {
        state.bindVertexArray(1);
        setEnabled(Usage.POSITION.getAttributeLocation(), true);
        setEnabled(Usage.PRIMARY_UV.getAttributeLocation(), true);
        setEnabled(Usage.NORMAL.getAttributeLocation(), true);

        int flags = state.currentClientArrayVertexFlagsForContext();
        assertEquals(VertexFlags.TEXTURE_BIT | VertexFlags.NORMAL_BIT, flags,
            "mask must not include COLOR_BIT when the VAO has no color attribute");
    }

    /**
     * Motivation: a colored model upload enables the color array too; the derived mask must
     * then include COLOR_BIT so the shader declares a_Color and reads the real data.
     */
    @Test
    void colorAttribDerivesColorBit() {
        state.bindVertexArray(1);
        setEnabled(Usage.POSITION.getAttributeLocation(), true);
        setEnabled(Usage.PRIMARY_UV.getAttributeLocation(), true);
        setEnabled(Usage.COLOR.getAttributeLocation(), true);

        int flags = state.currentClientArrayVertexFlagsForContext();
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
        state.bindVertexArray(1);
        setEnabled(Usage.POSITION.getAttributeLocation(), true);
        setEnabled(Usage.SECONDARY_UV.getAttributeLocation(), true);
        assertEquals(VertexFlags.BRIGHTNESS_BIT, state.currentClientArrayVertexFlagsForContext());

        state.bindVertexArray(2);
        assertEquals(0, state.currentClientArrayVertexFlagsForContext(),
            "binding a different VAO must not keep the previous VAO's brightness bit");
    }

    /**
     * Motivation: attributes can be toggled after binding; the derived mask must follow the
     * current enablement, so a leaked color enable that is later disabled disappears from the
     * mask.
     */
    @Test
    void disablingAttribDropsItsBit() {
        state.bindVertexArray(1);
        setEnabled(Usage.COLOR.getAttributeLocation(), true);
        assertEquals(VertexFlags.COLOR_BIT, state.currentClientArrayVertexFlagsForContext());

        setEnabled(Usage.COLOR.getAttributeLocation(), false);
        assertEquals(0, state.currentClientArrayVertexFlagsForContext());
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
        state.setPointer(location, 3, GL11.GL_FLOAT, false, stride, source, 0);
        final VertexAttribState.Attrib attrib = state.getAttrib(location);

        assertEquals(position, source.position());
        assertEquals(0, attrib.clientPointer.position());
        assertEquals(source.capacity() - position, attrib.clientPointer.capacity());
        assertEquals(ByteOrder.nativeOrder(), attrib.clientPointer.order());
        assertEquals(10.0f, attrib.readComponent(attrib.clientPointer, 0, 0));
        assertEquals(12.0f, attrib.readComponent(attrib.clientPointer, 0, 2));
        assertEquals(20.0f, attrib.readComponent(attrib.clientPointer, stride, 0));
        assertEquals(22.0f, attrib.readComponent(attrib.clientPointer, stride, 2));
    }

    /**
     * Motivation: client-attrib snapshots duplicate pointers once when captured and again when
     * restored; both copies must retain native byte order so the restored vertex data stays valid.
     */
    @Test
    void clientPointerReadsNativeFloatsAfterSnapshotCopyAndRestore() {
        final int position = 4;
        final int stride = 12;
        final ByteBuffer source = ByteBuffer.allocateDirect(position + stride * 2).order(ByteOrder.nativeOrder());
        source.putFloat(0, -100.0f);
        source.putFloat(position, 31.0f);
        source.putFloat(position + 4, 32.0f);
        source.putFloat(position + 8, 33.0f);
        source.putFloat(position + stride, 41.0f);
        source.putFloat(position + stride + 4, 42.0f);
        source.putFloat(position + stride + 8, 43.0f);
        source.limit(position + 12);
        source.position(position);

        final int location = Usage.POSITION.getAttributeLocation();
        state.setPointer(location, 3, GL11.GL_FLOAT, false, stride, source, 0);
        setEnabled(location, true);
        final GLContextIdentity identity = new GLContextIdentity(1L, 0xA11CEL, 404L);
        final VertexAttribState.Snapshot snapshot = state.snapshot(identity, 0, 0, 0, 0);

        final ByteBuffer replacement = ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder());
        replacement.putFloat(0, -1.0f);
        replacement.putFloat(4, -2.0f);
        replacement.putFloat(8, -3.0f);
        state.setPointer(location, 3, GL11.GL_FLOAT, false, 0, replacement, 0);
        setEnabled(location, false);

        state.restoreModel(snapshot, false);
        final VertexAttribState.Attrib restored = state.getAttrib(location);
        assertEquals(0, restored.clientPointer.position());
        assertEquals(ByteOrder.nativeOrder(), restored.clientPointer.order());
        assertEquals(31.0f, restored.readComponent(restored.clientPointer, 0, 0));
        assertEquals(33.0f, restored.readComponent(restored.clientPointer, 0, 2));
        assertEquals(41.0f, restored.readComponent(restored.clientPointer, stride, 0));
        assertEquals(43.0f, restored.readComponent(restored.clientPointer, stride, 2));
        assertEquals(position, source.position());
    }

    private void setEnabled(int index, boolean enabled) {
        state.setEnabledForContext(index, enabled);
    }
}
