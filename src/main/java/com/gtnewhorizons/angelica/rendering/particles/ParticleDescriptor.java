package com.gtnewhorizons.angelica.rendering.particles;

import net.minecraft.client.particle.Particle;

/**
 * Produces the instance payload of one particle without running its vertex writes.
 *
 * <p>A descriptor mirrors the arithmetic of the particle's own {@code renderParticle} so the caller
 * can skip that call entirely; returning {@code false} means the particle does not draw this frame
 * and nothing is submitted.</p>
 */
public interface ParticleDescriptor {

    /**
     * @param particle the particle about to be drawn
     * @param partialTicks interpolation factor of the current frame
     * @param out receives the instance payload
     * @param state receives the blend, depth and texture state the particle draws under
     * @return whether the particle produces geometry
     */
    boolean describe(Particle particle, float partialTicks, ParticleParams out, ParticleRenderState state);
}
