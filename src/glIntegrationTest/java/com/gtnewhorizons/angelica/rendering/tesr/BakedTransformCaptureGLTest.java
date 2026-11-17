package com.gtnewhorizons.angelica.rendering.tesr;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.DefaultVertexFormat;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Captures vertices with a modelview delta and checks that packed normals are rotated inside packed
 * space.
 *
 * <p>A normal that reaches the capture through {@code setPackedNormalRaw} must not run the base
 * tessellator's {@code setNormal} bookkeeping: that call can switch the vertex format in the middle
 * of a capture and, with a predefined format, silently drops the write. The packed value also has to
 * keep its three-byte precision instead of a decode/encode round trip.</p>
 */
class BakedTransformCaptureGLTest extends GLStateManagerIntegrationFixture {

    private static final int CAPTURE_BYTES = 4096;

    private BakedTransformCapture capture;

    @BeforeEach
    void beginCapture() {
        WindowBackends.useSdl();
        GLStateManager.bindDisplayContext(new Object(), context);
        GLStateManager.recreateDefaultVertexArray();
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glLoadIdentity();
        capture = new BakedTransformCapture(CAPTURE_BYTES);
        capture.setVertexFormat(DefaultVertexFormat.POSITION_TEXTURE);
        capture.begin();
    }

    @AfterEach
    void endCapture() {
        capture.end();
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glLoadIdentity();
    }

    private static int pack(int x, int y, int z) {
        return (x & 0xFF) | (y & 0xFF) << 8 | (z & 0xFF) << 16;
    }

    private static int component(int packed, int shift) {
        return (byte) (packed >> shift & 0xFF);
    }

    @Test
    void packedNormalIsRotatedWithTheModelViewDelta() {
        GLSMMatrices.glRotatef(90.0F, 0.0F, 1.0F, 0.0F);

        capture.setPackedNormalRaw(pack(0, 0, 127));

        final int rotated = capture.getPackedNormal();
        assertEquals(127, component(rotated, 0), "the +Z normal has to turn into +X");
        assertEquals(0, component(rotated, 8));
        assertEquals(0, component(rotated, 16));
    }

    @Test
    void packedNormalKeepsThreeBytePrecision() {
        GLSMMatrices.glRotatef(45.0F, 0.0F, 1.0F, 0.0F);

        capture.setPackedNormalRaw(pack(0, 0, 127));

        final int rotated = capture.getPackedNormal();
        assertEquals(90, component(rotated, 0), "a 45 degree turn lands on the rounded packed value");
        assertEquals(0, component(rotated, 8));
        assertEquals(90, component(rotated, 16));
        final int x = component(rotated, 0);
        final int z = component(rotated, 16);
        assertEquals(127.0, Math.sqrt((double) x * x + (double) z * z), 0.6,
            "the rotated normal keeps its packed length");
    }

    @Test
    void packedNormalWriteDoesNotSwitchTheVertexFormat() {
        GLSMMatrices.glRotatef(90.0F, 0.0F, 1.0F, 0.0F);

        capture.setPackedNormalRaw(pack(0, 0, 127));

        assertSame(DefaultVertexFormat.POSITION_TEXTURE, capture.getVertexFormat(),
            "a normal write must not widen the format of a capture that declares none");
        assertFalse(capture.hasNormals(), "the format flags stay as declared");
    }

    @Test
    void identityDeltaKeepsThePackedNormalBitForBit() {
        final int packed = pack(-127, 127, 1);

        capture.setPackedNormalRaw(packed);

        assertEquals(packed, capture.getPackedNormal(), "without a delta the packed value is untouched");
    }
}
