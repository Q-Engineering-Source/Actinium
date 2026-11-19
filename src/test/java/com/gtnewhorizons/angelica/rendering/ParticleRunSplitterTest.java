package com.gtnewhorizons.angelica.rendering;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleCloud;
import net.minecraft.client.particle.ParticleSpell;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the decision the run splitter makes and the gate in front of it.
 *
 * <p>A run is only split while a deferred pipeline owns it, and the split is what selects the
 * translucent particle program. The split's own bracket - draw the run, declare the new
 * translucency, reopen the run - is shared with every particle run and exercised through
 * {@code ParticleLitRun} on the third layer.</p>
 */
class ParticleRunSplitterTest {

    @BeforeEach
    void resetRun() {
        ParticleRunSplitter.beginRun();
    }

    @Test
    void treatsParticlesMarkedByTheirClassAsTranslucent() {
        assertTrue(ParticleRunSplitter.isTranslucent(new CloudProbe()));
        assertTrue(ParticleRunSplitter.isTranslucent(new SpellProbe()));
        assertFalse(ParticleRunSplitter.isTranslucent(new PlainParticle()));
    }

    @Test
    void leavesTheRunAloneWithoutADeferredPipeline() {
        final BufferBuilder run = new BufferBuilder(256);
        run.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        try {
            ParticleRunSplitter.splitIfNeeded(new CloudProbe(), run);

            assertFalse(ParticleRunSplitter.currentRunTranslucent(), "there is no particle program to switch to");
            assertEquals(0, run.getVertexCount(), "the run was neither drawn nor restarted");
            assertEquals(GL11.GL_QUADS, run.getDrawMode());
        } finally {
            run.finishDrawing();
        }
    }

    /** The particle bodies keep their constructors {@code protected}, so the probes widen only those. */
    private static final class CloudProbe extends ParticleCloud {
        CloudProbe() {
            super(null, 4.0D, 5.0D, 6.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    private static final class SpellProbe extends ParticleSpell {
        SpellProbe() {
            super(null, 4.0D, 5.0D, 6.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /** A particle whose class says nothing about translucency and which has no sprite. */
    private static final class PlainParticle extends Particle {
        PlainParticle() {
            super(null, 4.0D, 5.0D, 6.0D);
        }
    }
}
