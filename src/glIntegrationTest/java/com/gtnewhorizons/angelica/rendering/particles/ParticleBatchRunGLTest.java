package com.gtnewhorizons.angelica.rendering.particles;

import com.dhj.actinium.render.TessellatorDrawRouter;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.ffp.ParticleQuadMesh;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.gtnewhorizons.angelica.rendering.ParticleItemPickupRenderer;
import com.gtnewhorizons.angelica.rendering.ParticleRunSplitter;
import com.gtnewhorizons.angelica.rendering.tesr.TesrBatchRenderer;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleBatchRunGLTest extends GLStateManagerIntegrationFixture {

    private static final float ROT_X = 0.36F;
    private static final float ROT_XZ = 0.21F;
    private static final float ROT_Z = 0.48F;
    private static final float ROT_YZ = 0.13F;
    private static final float ROT_XY = 0.27F;

    private int drawsBefore;

    @BeforeEach
    void bindContext() {
        WindowBackends.useSdl();
        GLStateManager.bindDisplayContext(new Object(), context);
        GLStateManager.recreateDefaultVertexArray();
        installDefaultGlsmConfig();
        ShaderManager.enable();
        ParticleInstancer.clear();
        TesrBatchRenderer.INSTANCE.instanceRing().delete();
        drawsBefore = (int) ParticleInstancer.getDraws();
    }

    @AfterEach
    void restore() {
        ParticleInstancer.clear();
        ShaderManager.disable();
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "the particle run must leave no GL error");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void independentBeamDrawResumesTheOriginalParticleRun(boolean hasVanillaRunBoundary) {
        final BufferBuilder run = Tessellator.getInstance().getBuffer();
        final Particle laser = new CaptureParticle();
        beginRun();
        try {
            assertFalse(ParticleRunSplitter.ownsIndependentBoundary(laser));
            ParticleRunSplitter.renderIndependent(laser, () -> {
                if (hasVanillaRunBoundary && !ParticleRunSplitter.ownsIndependentBoundary(laser)) {
                    Tessellator.getInstance().draw();
                }
                run.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
                Tessellator.getInstance().draw();
                if (hasVanillaRunBoundary && !ParticleRunSplitter.ownsIndependentBoundary(laser)) {
                    run.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
                }
            });
            assertFalse(ParticleRunSplitter.ownsIndependentBoundary(laser));
            assertSame(DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP, run.getVertexFormat());
        } finally {
            endRun();
        }
    }

    @Test
    void nestedEntityDrawFlushesAndRestartsTheRun() {
        beginRun();
        assertTrue(ParticleRunSplitter.beforeNestedDraw(), "the open run has to be flushed before the entity uses the builder");
        assertEquals(0, Tessellator.getInstance().getBuffer().getVertexCount(),
            "the nested boundary consumes the current run instead of nesting another begin");
        ParticleRunSplitter.afterNestedDraw(true);

        assertTrue(ParticleRunSplitter.beforeNestedDraw(), "the reopened run accepts the same boundary again");
        ParticleRunSplitter.endParticles();
    }

    @Test
    void nestedPickupRendererCanUseTheSharedBuilder() {
        beginRun();
        final BufferBuilder run = Tessellator.getInstance().getBuffer();
        try {
            assertDoesNotThrow(() -> ParticleItemPickupRenderer.render(() -> {
                run.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
                run.finishDrawing();
            }, false));

            assertSame(DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP, run.getVertexFormat());
            assertEquals(0, run.getVertexCount());
        } finally {
            endRun();
        }
    }

    @Test
    void particleCollectionOwnsItsDrawAndResumesInstancing() {
        beginRun();
        final BufferBuilder run = Tessellator.getInstance().getBuffer();
        try {
            assertTrue(ParticleInstancer.renderParticle(new CaptureParticle(), run, null, 0.5F,
                ROT_X, ROT_XZ, ROT_Z, ROT_YZ, ROT_XY));

            final Particle collection = new CaptureParticle() {
                @Override
                public void renderParticle(BufferBuilder buffer, Entity entity, float partialTicks,
                                           float rotationX, float rotationZ, float rotationYZ,
                                           float rotationXY, float rotationXZ) {
                    buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
                    assertSame(run, buffer);
                    Tessellator.getInstance().draw();
                }
            };
            ParticleRunSplitter.renderIndependent(collection,
                () -> collection.renderParticle(run, null, 0.5F, ROT_X, ROT_Z, ROT_YZ, ROT_XY, ROT_XZ));

            assertEquals(drawsBefore + 1, ParticleInstancer.getDraws());
            assertSame(DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP, run.getVertexFormat());
            assertTrue(ParticleInstancer.renderParticle(new CaptureParticle(), run, null, 0.5F,
                ROT_X, ROT_XZ, ROT_Z, ROT_YZ, ROT_XY));
        } finally {
            endRun();
        }
        assertEquals(drawsBefore + 2, ParticleInstancer.getDraws());
        assertClosedBuilder(run);
    }

    @Test
    void drawsTheDecodedQuadAsInstances() {
        beginRun();
        final BufferBuilder run = Tessellator.getInstance().getBuffer();
        assertTrue(ParticleInstancer.renderParticle(new CaptureParticle(), run, null, 0.5F, ROT_X, ROT_XZ, ROT_Z, ROT_YZ, ROT_XY),
            "the batch accepts a particle that only writes vertices");
        ParticleInstancer.endLayer();

        assertEquals(drawsBefore + 1, (int) ParticleInstancer.getDraws(), "the decoded quad is one instanced draw");
        assertEquals(0, run.getVertexCount(), "a decoded quad is not written to the run");

        drawRun(run);
        assertClosedBuilder(run);
    }

    @Test
    void keepsTheVerticesTheBatchCannotTakeForTheRunsOwnDraw() {
        beginRun();
        final BufferBuilder run = Tessellator.getInstance().getBuffer();
        assertTrue(ParticleInstancer.renderParticle(new SpillingParticle(), run, null, 0.5F, ROT_X, ROT_XZ, ROT_Z, ROT_YZ, ROT_XY));
        ParticleInstancer.endLayer();

        assertEquals(8, run.getVertexCount(), "a particle that writes two quads is replayed into the run");
        assertEquals(drawsBefore, (int) ParticleInstancer.getDraws(), "an undecodable quad is not an instance draw");
        assertEquals(1, (int) ParticleInstancer.getSpilled(), "the replay is accounted for");

        drawRun(run);

        assertEquals(0, run.getVertexCount(), "the run's draw consumed the replayed vertices");
        assertClosedBuilder(run);
    }

    private static void beginRun() {
        ParticleRunSplitter.beginParticles();
        Tessellator.getInstance().getBuffer().begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        ParticleRunSplitter.beginRun();
        ParticleInstancer.beginLayer();
    }

    private static void endRun() {
        ParticleInstancer.endLayer();
        drawRun(Tessellator.getInstance().getBuffer());
    }

    // The harness does not install the Tessellator mixin that invokes the router in game.
    private static void drawRun(BufferBuilder run) {
        assertTrue(TessellatorDrawRouter.draw(run, null), "the router handles the particle format");
        ParticleRunSplitter.endParticles();
    }

    /** The run's draw closes the shared builder, so a fresh run can be opened on it. */
    private static void assertClosedBuilder(BufferBuilder run) {
        run.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        run.finishDrawing();
        assertEquals(0, run.getVertexCount(), "the reopened builder is empty");
    }

    /** A particle the registry has no descriptor for, writing one decodable quad. */
    private static class CaptureParticle extends Particle {

        CaptureParticle() {
            super(null, 4.0D, 5.0D, 6.0D);
        }

        @Override
        public int getBrightnessForRender(float partialTick) {
            return 0x00F00123;
        }

        @Override
        public void renderParticle(BufferBuilder buffer, Entity entityIn, float partialTicks, float rotationX,
                                   float rotationZ, float rotationYZ, float rotationXY, float rotationXZ) {
            final float half = 0.25F;
            for (int corner = 0; corner < ParticleQuadMesh.VERTEX_COUNT; corner++) {
                final float a = ParticleQuadMesh.cornerA(corner) * half;
                final float b = ParticleQuadMesh.cornerB(corner) * half;
                buffer.pos(
                        4.0D + ParticleQuadMesh.offsetX(a, b, rotationX, rotationXY),
                        5.0D + ParticleQuadMesh.offsetY(b, rotationZ),
                        6.0D + ParticleQuadMesh.offsetZ(a, b, rotationYZ, rotationXZ))
                    .tex(a < 0.0F ? 0.75F : 0.25F, b < 0.0F ? 0.5F : 0.125F)
                    .color(255, 255, 255, 255)
                    .lightmap(0x00F0, 0x00A0)
                    .endVertex();
            }
        }
    }

    /** A second quad makes the capture too large for one instance, so the whole capture is replayed. */
    private static final class SpillingParticle extends CaptureParticle {
        @Override
        public void renderParticle(BufferBuilder buffer, Entity entityIn, float partialTicks, float rotationX,
                                   float rotationZ, float rotationYZ, float rotationXY, float rotationXZ) {
            super.renderParticle(buffer, entityIn, partialTicks, rotationX, rotationZ, rotationYZ, rotationXY, rotationXZ);
            final float half = 0.125F;
            for (int corner = 0; corner < ParticleQuadMesh.VERTEX_COUNT; corner++) {
                final float a = ParticleQuadMesh.cornerA(corner) * half;
                final float b = ParticleQuadMesh.cornerB(corner) * half;
                buffer.pos(
                        4.0D + ParticleQuadMesh.offsetX(a, b, rotationX, rotationXY),
                        5.0D + ParticleQuadMesh.offsetY(b, rotationZ),
                        6.5D + ParticleQuadMesh.offsetZ(a, b, rotationYZ, rotationXZ))
                    .tex(a < 0.0F ? 0.75F : 0.25F, b < 0.0F ? 0.5F : 0.125F)
                    .color(255, 255, 255, 255)
                    .lightmap(0x00F0, 0x00A0)
                    .endVertex();
            }
        }
    }
}
