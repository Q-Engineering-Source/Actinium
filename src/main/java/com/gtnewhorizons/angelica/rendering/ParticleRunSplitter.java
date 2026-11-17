package com.gtnewhorizons.angelica.rendering;

import com.gtnewhorizons.angelica.client.rendering.DeferredDrawBatcher;
import com.gtnewhorizons.angelica.rendering.particles.ParticleInstancer;
import dhj.embeddedt.embeddium.impl.render.chunk.sprite.SpriteTransparencyLevel;
import net.coderbot.iris.Iris;
import net.coderbot.iris.layer.GbufferPrograms;
import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleCloud;
import net.minecraft.client.particle.ParticleFirework;
import net.minecraft.client.particle.ParticleSpell;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Splits a particle run between {@code gbuffers_particles} and {@code gbuffers_particles_translucent}.
 *
 * <p>1.12.2 groups a run by layer and depth mask only, so one run can hold particles whose sprites
 * are opaque cutouts and particles whose sprites use partial alpha. The pipeline picks its
 * particle program from the declared translucency, which therefore has to change when the run does:
 * the current run is drawn, the declaration is updated, and a new run is opened for the remaining
 * particles. The instance layer and the deferred batch follow the same boundary, because both own
 * vertices the draw has to flush first.</p>
 *
 * <p>The split is only taken while a deferred pipeline owns the run; without one the declaration has
 * no consumer and the vanilla grouping stays untouched. It is independent of the batch: a run that
 * the batch declined, or one drawn with the batch switched off, still has to reach the translucent
 * particle program.</p>
 */
public final class ParticleRunSplitter {

    private static boolean runTranslucent;
    private static Boolean previousDeclaration;

    private ParticleRunSplitter() {}

    /**
     * Opens the particle pass and declares it opaque.
     *
     * <p>The declaration outlives a single run and the pipeline reads it for other phases too, so it
     * is scoped to the pass and restored by {@link #endParticles}; leaving it behind would make the
     * entity pass after the particles select the opaque program.</p>
     */
    public static void beginParticles() {
        runTranslucent = false;
        previousDeclaration = GbufferPrograms.beginTranslucencyDeclaration(Boolean.FALSE);
    }

    public static void endParticles() {
        GbufferPrograms.endTranslucencyDeclaration(previousDeclaration);
        previousDeclaration = null;
    }

    /** Starts a run that is not translucent, matching the pipeline's own default. */
    public static void beginRun() {
        runTranslucent = false;
        GbufferPrograms.setTranslucencyDeclaration(Boolean.FALSE);
    }

    /**
     * Ends the current run and starts a new one when the particle's translucency differs from the
     * run's.
     *
     * @param particle particle about to be drawn
     * @param run the buffer of the current run, restarted for the new run
     */
    public static void splitIfNeeded(Particle particle, BufferBuilder run) {
        if (!deferredPipelineActive()) return;

        final boolean translucent = isTranslucent(particle);
        if (translucent == runTranslucent) return;

        ParticleInstancer.endLayer();
        final boolean batcherWasActive = DeferredDrawBatcher.isActive();
        if (batcherWasActive) DeferredDrawBatcher.exitAndFlush();
        Tessellator.getInstance().draw();

        runTranslucent = translucent;
        GbufferPrograms.setTranslucencyDeclaration(translucent);

        run.begin(run.getDrawMode(), run.getVertexFormat());
        if (batcherWasActive) DeferredDrawBatcher.enter();
        ParticleInstancer.beginLayer();
    }

    private static boolean deferredPipelineActive() {
        return Iris.enabled && Iris.getPipelineManager().getPipelineNullable() instanceof DeferredWorldRenderingPipeline;
    }

    public static boolean currentRunTranslucent() {
        return runTranslucent;
    }

    /**
     * @param particle particle to classify
     * @return whether the particle belongs to the translucent particle program
     */
    public static boolean isTranslucent(Particle particle) {
        final TextureAtlasSprite texture = particle.particleTexture;
        if (texture != null) {
            return SpriteTransparencyLevel.Holder.getTransparencyLevel(texture) == SpriteTransparencyLevel.TRANSLUCENT;
        }

        return particle instanceof ParticleFirework.Spark
            || particle instanceof ParticleFirework.Overlay
            || particle instanceof ParticleFirework.Starter
            || particle instanceof ParticleSpell
            || particle instanceof ParticleCloud;
    }
}
