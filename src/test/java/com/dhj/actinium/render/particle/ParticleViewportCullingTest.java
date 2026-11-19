package com.dhj.actinium.render.particle;

import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;
import dhj.embeddedt.embeddium.impl.render.viewport.frustum.Frustum;
import net.minecraft.client.particle.Particle;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the viewport predicate the particle call sites run before anything else.
 *
 * <p>The predicate is unconditional: a batched particle never reaches the vanilla call, so there is
 * no other place where an invisible particle could be dropped, and an external mod adding its own
 * test on top only repeats one bounding-box comparison.</p>
 */
class ParticleViewportCullingTest {

    /** Visible window in camera space, expressed in the viewport's own local coordinates. */
    private static final Frustum WINDOW = (minX, minY, minZ, maxX, maxY, maxZ) -> maxX > 0.0F && minX < 8.0F;

    @Test
    void keepsAParticleInsideTheViewport() {
        assertTrue(ParticleViewportCulling.isVisible(particleAt(2.0D), viewport()));
    }

    @Test
    void dropsAParticleOutsideTheViewport() {
        assertFalse(ParticleViewportCulling.isVisible(particleAt(-40.0D), viewport()));
    }

    @Test
    void keepsEveryParticleWithoutAViewport() {
        assertTrue(ParticleViewportCulling.isVisible(particleAt(-40.0D), null));
    }

    private static Viewport viewport() {
        return new Viewport(WINDOW, new Vector3d());
    }

    private static Particle particleAt(double x) {
        return new Probe(x);
    }

    /** The particle constructors are protected, so the test widens only the constructor. */
    private static final class Probe extends Particle {
        Probe(double x) {
            super(null, x, 0.0D, 0.0D);
        }
    }
}
