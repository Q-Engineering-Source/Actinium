package com.gtnewhorizons.angelica.rendering.particles;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * Stands in for the shared run buffer while one particle writes its own vertices.
 *
 * <p>1.12.2 particles write straight into the run {@link BufferBuilder}, so a batched draw has to
 * redirect that buffer and inspect the result afterwards. The capture keeps whatever the particle
 * wrote, and either the caller decodes it into one instance or {@link #replayInto} appends the
 * original vertices to the run, in the order they were written.</p>
 *
 * <p>{@link #MAX_VERTICES} bounds a decodable quad. A particle that writes a different number of
 * vertices cannot be one instanced quad, so the whole capture is spilled. The buffer is finished
 * between captures, which is the only way to start a fresh vertex range on a {@code BufferBuilder}
 * without allocating a new one per particle.</p>
 */
public final class ParticleCaptureBuffer {

    public static final int MAX_VERTICES = 4;

    private static final int[] EMPTY = new int[0];
    private static final int CAPACITY_INTS = 8 * ParticleQuadDecoder.STRIDE_INTS;

    private final BufferBuilder buffer = new BufferBuilder(CAPACITY_INTS);
    private boolean open;
    private int captured;
    private boolean spilled;
    private int[] raw = EMPTY;

    public void begin() {
        if (open) {
            buffer.finishDrawing();
        }
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        open = true;
        captured = 0;
        spilled = false;
        raw = EMPTY;
    }

    /** @return the buffer the particle's {@code renderParticle} writes into */
    public BufferBuilder buffer() {
        return buffer;
    }

    /** @return how many vertices the particle wrote for the current capture */
    public int captured() {
        seal();
        return captured;
    }

    /** @return whether the capture cannot be one instanced quad and has to be replayed */
    public boolean spilled() {
        seal();
        return spilled;
    }

    /** @return the captured vertices in the run's vertex format, in write order */
    public int[] raw() {
        seal();
        return raw;
    }

    /** Appends the captured vertices to the run, keeping their original order. */
    public void replayInto(BufferBuilder run) {
        seal();
        if (raw.length > 0) {
            run.addVertexData(raw);
        }
    }

    private void seal() {
        if (!open) return;
        open = false;
        captured = buffer.getVertexCount();
        raw = captured > 0 ? buffer.getVertexState().getRawBuffer() : EMPTY;
        spilled = captured != 0 && captured != MAX_VERTICES;
        buffer.finishDrawing();
    }
}
