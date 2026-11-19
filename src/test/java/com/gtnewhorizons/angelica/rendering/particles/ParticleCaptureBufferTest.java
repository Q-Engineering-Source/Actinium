package com.gtnewhorizons.angelica.rendering.particles;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the capture buffer that replaces 1.12.2's shared particle {@link BufferBuilder}: a particle
 * writes into it instead of the run, and whatever it wrote is either decoded into one instance or
 * replayed into the run in the exact order it was written.
 */
class ParticleCaptureBufferTest {

    private static final int VERTEX_INTS = 7;

    private ParticleCaptureBuffer capture;

    @BeforeEach
    void setUp() {
        capture = new ParticleCaptureBuffer();
    }

    @Test
    void keepsAFourVertexQuadForDecoding() {
        capture.begin();
        writeQuad(capture.buffer(), 1.0F);

        assertEquals(ParticleCaptureBuffer.MAX_VERTICES, capture.captured());
        assertFalse(capture.spilled());
        assertEquals(ParticleCaptureBuffer.MAX_VERTICES * VERTEX_INTS, capture.raw().length);
    }

    @Test
    void spillsAFifthVertexAndReplaysEverythingInTheOriginalOrder() {
        capture.begin();
        writeQuad(capture.buffer(), 1.0F);
        // Fifth vertex: a wider particle that cannot be one instanced quad.
        capture.buffer().pos(5.0, 6.0, 7.0).tex(0.5, 0.75).color(1, 2, 3, 4).lightmap(0x20, 0x30).endVertex();

        assertTrue(capture.spilled());
        assertEquals(5, capture.captured());

        final BufferBuilder run = new BufferBuilder(256);
        run.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        capture.replayInto(run);

        assertEquals(5, run.getVertexCount());
        assertArrayEquals(capture.raw(), run.getVertexState().getRawBuffer());
    }

    @Test
    void replaysAPartialQuadInTheOriginalOrder() {
        capture.begin();
        capture.buffer().pos(1.0, 2.0, 3.0).tex(0.25, 0.5).color(9, 8, 7, 6).lightmap(0x11, 0x22).endVertex();
        capture.buffer().pos(4.0, 5.0, 6.0).tex(0.75, 0.25).color(9, 8, 7, 6).lightmap(0x11, 0x22).endVertex();

        assertTrue(capture.spilled());
        assertEquals(2, capture.captured());

        final BufferBuilder run = new BufferBuilder(256);
        run.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        capture.replayInto(run);

        final int[] replayed = run.getVertexState().getRawBuffer();
        assertEquals(Float.floatToRawIntBits(1.0F), replayed[0]);
        assertEquals(Float.floatToRawIntBits(4.0F), replayed[VERTEX_INTS]);
        assertEquals(0x0011_0022, replayed[VERTEX_INTS + 6]);
    }

    @Test
    void startsTheNextCaptureFromAnEmptyBuffer() {
        capture.begin();
        writeQuad(capture.buffer(), 1.0F);
        assertEquals(4, capture.captured());

        capture.begin();

        assertEquals(0, capture.captured());
        assertFalse(capture.spilled());
    }

    @Test
    void writesTheRunLayoutTheParticlesRead() {
        capture.begin();
        writeQuad(capture.buffer(), 2.0F);

        final int[] raw = capture.raw();
        // Corner 0 sits at the centre minus half an extent on both axes.
        assertEquals(1.0F - 0.1F * 2.0F, Float.intBitsToFloat(raw[0]));
        assertEquals(2.0F - 0.1F * 2.0F, Float.intBitsToFloat(raw[1]));
        assertEquals(3.0F, Float.intBitsToFloat(raw[2]));
        assertEquals(0.5F, Float.intBitsToFloat(raw[3]));
        assertEquals(0.5F, Float.intBitsToFloat(raw[4]));
        assertEquals(0x04030201, raw[5]);
        assertEquals(0x0020_0030, raw[6]);
    }

    private static void writeQuad(BufferBuilder buffer, float scale) {
        final float half = 0.1F * scale;
        final float[] xs = { -half, -half, half, half };
        final float[] ys = { -half, half, half, -half };
        for (int corner = 0; corner < 4; corner++) {
            buffer.pos(1.0 + xs[corner], 2.0 + ys[corner], 3.0)
                .tex(corner < 2 ? 0.5F : 0.25F, corner == 1 || corner == 2 ? 0.25F : 0.5F)
                .color(1, 2, 3, 4)
                .lightmap(0x20, 0x30)
                .endVertex();
        }
    }
}
