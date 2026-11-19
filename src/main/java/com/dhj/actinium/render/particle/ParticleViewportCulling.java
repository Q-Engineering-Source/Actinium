package com.dhj.actinium.render.particle;

import com.dhj.actinium.mixinterface.features.iris.ParticleCullingBypass;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;
import net.minecraft.client.particle.Particle;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;

/**
 * Applies the last world viewport to particle rendering while accounting for compound particles.
 *
 * <p>The answer is a predicate rather than a draw, so the caller can decide what an invisible
 * particle costs: the batch must not take it and the vanilla write must not happen. A particle the
 * batch takes never reaches the vanilla call, so this test always has to run here.</p>
 */
public final class ParticleViewportCulling {

    private ParticleViewportCulling() {
    }

    /**
     * @param particle particle about to be drawn
     * @param viewport viewport captured for the current world pass, or {@code null} when no culling
     *                 data is available
     * @return whether the particle has to be submitted
     */
    public static boolean isVisible(Particle particle, Viewport viewport) {
        if (viewport == null || particle instanceof ParticleCullingBypass) {
            return true;
        }
        final AxisAlignedBB box = particle.getBoundingBox();
        return box == null
            || box == TileEntity.INFINITE_EXTENT_AABB
            || viewport.isBoxVisible(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }
}
