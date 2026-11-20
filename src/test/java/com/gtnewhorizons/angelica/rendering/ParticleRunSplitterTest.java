package com.gtnewhorizons.angelica.rendering;

import net.coderbot.iris.layer.GbufferPrograms;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleCloud;
import net.minecraft.client.particle.ParticleSpell;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleRunSplitterTest {

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
        ParticleRunSplitter.beginParticles();
        ParticleRunSplitter.beginRun();
        try {
            ParticleRunSplitter.splitIfNeeded(new CloudProbe(), run);

            assertFalse(ParticleRunSplitter.currentRunTranslucent(), "there is no particle program to switch to");
            assertEquals(0, run.getVertexCount(), "the run was neither drawn nor restarted");
            assertEquals(GL11.GL_QUADS, run.getDrawMode());
        } finally {
            run.finishDrawing();
            ParticleRunSplitter.endParticles();
        }
    }

    @Test
    void selfDrawingLitParticlesOwnTheSharedBufferAcrossConsecutiveDraws() {
        final BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        final SelfDrawingParticle particle = new SelfDrawingParticle();
        final Boolean previous = GbufferPrograms.beginTranslucencyDeclaration(Boolean.TRUE);
        try {
            final Runnable originalDraw = () -> particle.renderParticle(buffer, null, 0.5F, 1, 1, 0, 0, 0);

            ParticleRunSplitter.renderWithTranslucency(particle, originalDraw);
            ParticleRunSplitter.renderWithTranslucency(particle, originalDraw);

            assertEquals(2, particle.drawCount);
            assertEquals(Boolean.TRUE, GbufferPrograms.getDeclaredTranslucency());
        } finally {
            GbufferPrograms.endTranslucencyDeclaration(previous);
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

    private static final class SelfDrawingParticle extends Particle {
        private int drawCount;

        SelfDrawingParticle() {
            super(null, 0, 0, 0);
        }

        @Override
        public void renderParticle(BufferBuilder buffer, Entity entity, float partialTicks, float rotationX,
                                   float rotationZ, float rotationYZ, float rotationXY, float rotationXZ) {
            assertEquals(Boolean.FALSE, GbufferPrograms.getDeclaredTranslucency());
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
            Tessellator.getInstance().draw();
            drawCount++;
        }
    }
}
