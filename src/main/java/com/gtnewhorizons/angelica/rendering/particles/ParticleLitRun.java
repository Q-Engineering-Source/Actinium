package com.gtnewhorizons.angelica.rendering.particles;

import com.gtnewhorizons.angelica.glsm.ITessellatorData;
import com.gtnewhorizons.angelica.rendering.ParticleRunSplitter;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * Opens and closes the buffer the third particle layer is written into.
 *
 * <p>1.12.2 sends {@code renderLitParticles} through the shared {@link BufferBuilder} without opening
 * a run or drawing one, so the layer itself neither brackets nor flushes what its particles write:
 * the vertices stay in the shared builder for whatever draws it next. This pass gives the layer the
 * same lifecycle the others have: the buffer is opened before the loop, the particles are submitted
 * to the batch like any other, and the run is drawn when the loop ends, which also carries the
 * vertices the decoder could not turn into an instance.</p>
 *
 * <p><b>Visibility:</b> the layer draws its own run now, so its particles reach the screen through
 * this path; their geometry and their frustum test are unchanged.</p>
 */
public final class ParticleLitRun {

    private ParticleLitRun() {}

    /**
     * Opens the lit run. A builder another renderer left open is finished first: the tessellator is a
     * process-wide singleton and the lit pass is the first thing each frame that opens it.
     */
    public static void begin() {
        final BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        if (buffer instanceof ITessellatorData data && data.isDrawing()) {
            buffer.finishDrawing();
        }
        ParticleRunSplitter.beginParticles();
        ParticleRunSplitter.beginRun();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        ParticleInstancer.beginLayer();
    }

    /** Draws the instances the run collected, then the vertices it could not batch. */
    public static void end() {
        ParticleInstancer.endLayer();
        Tessellator.getInstance().draw();
        ParticleRunSplitter.endParticles();
    }
}
