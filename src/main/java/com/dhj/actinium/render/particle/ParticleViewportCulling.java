package com.dhj.actinium.render.particle;

import com.dhj.actinium.mixinterface.features.iris.ParticleCullingBypass;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;

/**
 * Applies the last world viewport to particle rendering while accounting for compound particles.
 */
public final class ParticleViewportCulling {
    private ParticleViewportCulling() {
        // Prevent instances of this stateless culling utility.
    }

    /**
     * Renders a particle when its bounds intersect the viewport or its bounds do not describe its geometry.
     *
     * @param particle particle whose geometry will be submitted when visible
     * @param viewport viewport captured for the current world pass, or {@code null} when no culling data is available
     * @param buffer vertex buffer receiving the particle geometry
     * @param entity camera entity supplied by the particle manager
     * @param partialTicks interpolation factor for the current frame
     * @param rotationX camera billboard rotation component
     * @param rotationZ camera billboard rotation component
     * @param rotationYZ camera billboard rotation component
     * @param rotationXY camera billboard rotation component
     * @param rotationXZ camera billboard rotation component
     */
    public static void renderIfVisible(
        Particle particle,
        Viewport viewport,
        BufferBuilder buffer,
        Entity entity,
        float partialTicks,
        float rotationX,
        float rotationZ,
        float rotationYZ,
        float rotationXY,
        float rotationXZ
    ) {
        AxisAlignedBB box = particle.getBoundingBox();
        if (viewport == null
                || particle instanceof ParticleCullingBypass
                || box == null
                || box == TileEntity.INFINITE_EXTENT_AABB
                || viewport.isBoxVisible(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) {
            particle.renderParticle(buffer, entity, partialTicks, rotationX, rotationZ, rotationYZ, rotationXY, rotationXZ);
        }
    }
}
