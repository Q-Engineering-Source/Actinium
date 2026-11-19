package com.gtnewhorizons.angelica.rendering.particles;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.ITessellatorData;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.ffp.ParticleQuadMesh;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.dhj.actinium.render.TessellatorDrawRouter;
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
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the third particle layer through the run {@code ParticleLitRun} opens: the shared builder is
 * opened for the particles, the batch takes what it can decode, and the run's own draw carries the
 * rest.
 *
 * <p>Vanilla writes those particles into the shared builder without ever drawing it, so the run is
 * what makes them reach the screen. The run's draw goes through the tessellator hook, which the
 * integration harness cannot install (it runs without the game's mixins); the test therefore performs
 * that one step through the router the hook calls, and every other step is the production code.</p>
 */
class ParticleLitRunGLTest extends GLStateManagerIntegrationFixture {

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
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "the lit particle run must leave no GL error");
    }

    @Test
    void rejectsABuilderLeftOpenByAnotherRenderer() {
        final BufferBuilder run = Tessellator.getInstance().getBuffer();
        new OpenBufferView(run);
        boolean rejected = false;
        try {
            ParticleLitRun.begin();
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        if (rejected) {
            run.finishDrawing();
        }
        assertTrue(rejected, "an unfinished foreign draw must not be committed under the particle pass state");
    }

    @Test
    void nestedEntityDrawFlushesAndRestartsTheRun() {
        ParticleLitRun.begin();
        assertTrue(ParticleRunSplitter.beforeNestedDraw(), "the open run has to be flushed before the entity uses the builder");
        assertEquals(0, Tessellator.getInstance().getBuffer().getVertexCount(),
            "the nested boundary consumes the current run instead of nesting another begin");
        ParticleRunSplitter.afterNestedDraw(true);

        assertTrue(ParticleRunSplitter.beforeNestedDraw(), "the reopened run accepts the same boundary again");
    }

    @Test
    void vanillaPickupEntityCanUseTheSharedBuilder() {
        ParticleLitRun.begin();
        final BufferBuilder run = Tessellator.getInstance().getBuffer();
        try {
            assertDoesNotThrow(() -> ParticleItemPickupRenderer.render(() -> {
                run.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
                run.finishDrawing();
            }, false));

            assertSame(DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP, run.getVertexFormat());
            assertEquals(0, run.getVertexCount());
        } finally {
            ParticleLitRun.end();
        }
    }

    @Test
    void particleCollectionOwnsItsDrawAndResumesInstancing() {
        ParticleLitRun.begin();
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
            ParticleLitRun.end();
        }
        assertEquals(drawsBefore + 2, ParticleInstancer.getDraws());
        assertClosedBuilder(run);
    }

    @Test
    void opensTheRunAndDrawsTheDecodedQuadAsInstances() {
        ParticleLitRun.begin();
        final BufferBuilder run = Tessellator.getInstance().getBuffer();
        assertEquals(GL11.GL_QUADS, run.getDrawMode(), "the lit pass opens a quad run");
        assertEquals(DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP, run.getVertexFormat(),
            "the lit pass uses the particle vertex format, so a particle can write into it");
        assertTrue(ParticleInstancer.renderParticle(new CaptureParticle(), run, null, 0.5F, ROT_X, ROT_XZ, ROT_Z, ROT_YZ, ROT_XY),
            "a lit particle joins the batch through the same entry the other layers use");
        ParticleInstancer.endLayer();

        assertEquals(drawsBefore + 1, (int) ParticleInstancer.getDraws(), "the decoded quad is one instanced draw");
        assertEquals(0, run.getVertexCount(), "a decoded quad is not written to the run");

        drawRun(run);
        assertTrue(drawsBefore + 1 <= (int) ParticleInstancer.getDraws(), "the empty run adds no instance draw");
        assertClosedBuilder(run);
    }

    @Test
    void keepsTheVerticesTheBatchCannotTakeForTheRunsOwnDraw() {
        ParticleLitRun.begin();
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

    /**
     * The step {@code ParticleLitRun.end} performs through {@code Tessellator.draw}, which the
     * tessellator hook routes here.
     */
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

    private static final class OpenBufferView implements ITessellatorData {
        OpenBufferView(BufferBuilder buffer) {
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        }

        @Override
        public boolean isDrawing() {
            return true;
        }

        @Override
        public void setDrawing(boolean drawing) {
        }

        @Override
        public int getVertexCount() {
            return 0;
        }

        @Override
        public int[] getRawBuffer() {
            return new int[0];
        }

        @Override
        public int getRawBufferIndex() {
            return 0;
        }

        @Override
        public int getRawBufferSize() {
            return 0;
        }

        @Override
        public void setRawBufferSize(int size) {
        }

        @Override
        public void setRawBuffer(int[] buffer) {
        }

        @Override
        public int getDrawMode() {
            return GL11.GL_QUADS;
        }

        @Override
        public boolean hasTexture() {
            return true;
        }

        @Override
        public boolean hasColor() {
            return true;
        }

        @Override
        public boolean hasNormals() {
            return true;
        }

        @Override
        public boolean hasBrightness() {
            return true;
        }

        @Override
        public void angelica$reset() {
        }
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
