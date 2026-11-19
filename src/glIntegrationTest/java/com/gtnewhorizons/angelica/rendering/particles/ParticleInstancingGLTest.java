package com.gtnewhorizons.angelica.rendering.particles;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.ffp.ParticleInstancedAttribs;
import com.gtnewhorizons.angelica.glsm.ffp.ParticleQuadMesh;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.gtnewhorizons.angelica.glsm.hooks.DrawCallObserver;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.rendering.tesr.InstanceRing;
import com.gtnewhorizons.angelica.rendering.tesr.MeshBuffer;
import com.gtnewhorizons.angelica.rendering.tesr.TesrBatchRenderer;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAddress0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives real instanced particle draws against a context: the uploaded instance bytes, the single
 * draw call a run produces, the state a particle run samples, and the per-vertex replay used when no
 * variant is available.
 */
class ParticleInstancingGLTest extends GLStateManagerIntegrationFixture {

    private static final float HALF = 0.35F;
    private static final int COLOR = 0x80402010;
    private static final int LIGHT = 0x00F000A0;

    private static final float ROT_X = 0.36F;
    private static final float ROT_XZ = 0.21F;
    private static final float ROT_Z = 0.48F;
    private static final float ROT_YZ = 0.13F;
    private static final float ROT_XY = 0.27F;

    private final ParticleParams decoded = new ParticleParams();
    private DrawCallObserver previousObserver;

    @BeforeEach
    void bindContext() {
        WindowBackends.useSdl();
        GLStateManager.bindDisplayContext(new Object(), context);
        GLStateManager.recreateDefaultVertexArray();
        installDefaultGlsmConfig();
        ShaderManager.enable();
        ParticleInstancer.clear();
        TesrBatchRenderer.INSTANCE.instanceRing().delete();
    }

    @AfterEach
    void restore() {
        GLSMHooks.drawCallObserver = previousObserver;
        ParticleInstancer.clear();
        ShaderManager.disable();
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "the particle draw path must leave no GL error");
    }

    @Test
    void uploadsTheInstanceLayoutAndDrawsTheRunOnce() {
        final List<Integer> observedFlags = new ArrayList<>();
        previousObserver = GLSMHooks.drawCallObserver;
        GLSMHooks.drawCallObserver = observedFlags::add;

        final long capturedBefore = ParticleInstancer.getCaptured();
        final long drawsBefore = ParticleInstancer.getDraws();
        final BufferBuilder run = beginRun();
        ParticleInstancer.beginLayer();
        assertTrue(ParticleInstancer.renderParticle(new CaptureParticle(), run, null, 0.5F, ROT_X, ROT_XZ, ROT_Z, ROT_YZ, ROT_XY),
            "a particle with no descriptor has to be captured");
        ParticleInstancer.endLayer();

        assertEquals(capturedBefore + 1, ParticleInstancer.getCaptured());
        assertEquals(drawsBefore + 1, ParticleInstancer.getDraws(), "one run with one quad is one instanced draw");
        assertEquals(0, run.getVertexCount(), "a decoded quad must not also be written to the run");

        final ByteBuffer instance = readBackInstances();
        assertEquals(4.0F, instance.getFloat(ParticleInstancedAttribs.OFFSET_CENTER_HALF));
        assertEquals(5.0F, instance.getFloat(ParticleInstancedAttribs.OFFSET_CENTER_HALF + 4));
        assertEquals(6.0F, instance.getFloat(ParticleInstancedAttribs.OFFSET_CENTER_HALF + 8));
        assertEquals(HALF, instance.getFloat(ParticleInstancedAttribs.OFFSET_CENTER_HALF + 12), 1.0e-5F);
        assertEquals(0.75F, instance.getFloat(ParticleInstancedAttribs.OFFSET_UV), 1.0e-5F);
        assertEquals(0.5F, instance.getFloat(ParticleInstancedAttribs.OFFSET_UV + 4), 1.0e-5F);
        assertEquals(0.25F, instance.getFloat(ParticleInstancedAttribs.OFFSET_UV + 8), 1.0e-5F);
        assertEquals(0.125F, instance.getFloat(ParticleInstancedAttribs.OFFSET_UV + 12), 1.0e-5F);
        assertEquals(COLOR, instance.getInt(ParticleInstancedAttribs.OFFSET_COLOR));
        assertEquals(LIGHT & 0xFFFF, instance.getFloat(ParticleInstancedAttribs.OFFSET_LIGHTMAP));
        assertEquals((LIGHT >>> 16) & 0xFFFF, instance.getFloat(ParticleInstancedAttribs.OFFSET_LIGHTMAP + 4));

        assertEquals(1, observedFlags.size(), "the batch submits exactly one draw");
        final int flags = observedFlags.get(0);
        assertTrue((flags & VertexFlags.TEXTURE_BIT) != 0, "the particle program samples the bound texture");
        assertTrue((flags & VertexFlags.COLOR_BIT) != 0, "the particle program takes its colour from the instance");
        assertTrue((flags & VertexFlags.BRIGHTNESS_BIT) != 0, "the particle program takes its lightmap from the instance");
    }

    @Test
    void groupsParticlesByTheStateTheyLeaveBehind() {
        GLSMEnable.glEnable(GL11.GL_BLEND);
        final long drawsBefore = ParticleInstancer.getDraws();
        final BufferBuilder run = beginRun();
        ParticleInstancer.beginLayer();
        assertTrue(ParticleInstancer.renderParticle(new CaptureParticle(), run, null, 0.5F, ROT_X, ROT_XZ, ROT_Z, ROT_YZ, ROT_XY));
        assertTrue(ParticleInstancer.renderParticle(new BlendDisablingParticle(), run, null, 0.5F, ROT_X, ROT_XZ, ROT_Z, ROT_YZ, ROT_XY));
        ParticleInstancer.endLayer();

        assertEquals(drawsBefore + 2, ParticleInstancer.getDraws(),
            "the state is sampled after the particle ran, so a particle that changed blend starts its own group");
    }

    @Test
    void leavesTheParticleToTheCallerWhenNoFixedFunctionProgramExists() {
        ShaderManager.disable();
        final long drawsBefore = ParticleInstancer.getDraws();
        final BufferBuilder run = beginRun();
        ParticleInstancer.beginLayer();

        assertFalse(ParticleInstancer.renderParticle(new CaptureParticle(), run, null, 0.5F, ROT_X, ROT_XZ, ROT_Z, ROT_YZ, ROT_XY));
        ParticleInstancer.endLayer();

        assertEquals(drawsBefore, ParticleInstancer.getDraws(), "without a fixed function program the run keeps its vertices");
        assertEquals(0, run.getVertexCount());
    }

    @Test
    void replayingOneGroupIntoTheRunReproducesTheQuad() {
        final ParticleRenderState state = new ParticleRenderState();
        final ByteBuffer list = MeshBuffer.ensureCapacity(null, ParticleInstancedAttribs.STRIDE, true);
        ParticleInstancedAttribs.writeInstance(memAddress0(list), 4.0F, 5.0F, 6.0F, HALF, 0.75F, 0.5F, 0.25F, 0.125F, COLOR, LIGHT);
        list.position(ParticleInstancedAttribs.STRIDE);

        final BufferBuilder run = beginRun();
        ParticleInstancer.replayGroupAsVertices(state, list, 1, run, ROT_X, ROT_XZ, ROT_Z, ROT_YZ, ROT_XY);

        assertEquals(4, run.getVertexCount());
        assertTrue(ParticleQuadDecoder.decode(run.getVertexState().getRawBuffer(), ROT_X, ROT_XZ, ROT_Z, ROT_YZ, ROT_XY, decoded),
            "the replayed vertices are the quad the decoder recognises");
        assertEquals(4.0F, decoded.centerX, 1.0e-5F);
        assertEquals(5.0F, decoded.centerY, 1.0e-5F);
        assertEquals(6.0F, decoded.centerZ, 1.0e-5F);
        assertEquals(HALF, decoded.half, 1.0e-5F);
        assertEquals(0.75F, decoded.u0, 1.0e-5F);
        assertEquals(0.125F, decoded.v1, 1.0e-5F);
        assertEquals(COLOR, decoded.colorABGR);
        assertEquals(LIGHT, decoded.brightness);
    }

    @Test
    void samplesAndRestoresTheRunState() {
        final ParticleRenderState state = new ParticleRenderState();
        GLSMEnable.glEnable(GL11.GL_BLEND);
        GLSMBlend.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GLSMDepthStencilAlpha.glDepthMask(false);

        state.sample();

        GLSMBlend.glBlendFunc(GL11.GL_SRC_COLOR, GL11.GL_ONE);
        GLSMDepthStencilAlpha.glDepthMask(true);
        GLSMEnable.glDisable(GL11.GL_BLEND);

        state.apply();

        assertTrue(GLSMBlend.isEffectiveBlendEnabled(), "the sampled blend enable is restored");
        assertFalse(GLSMDepthStencilAlpha.isEffectiveDepthMaskEnabled(), "the sampled depth mask is restored");
        assertEquals(GLSMTextures.getBoundTextureForServerState(), state.texture);
    }

    private static BufferBuilder beginRun() {
        final BufferBuilder run = new BufferBuilder(256);
        run.begin(GL11.GL_QUADS, DefaultVertexFormats.PARTICLE_POSITION_TEX_COLOR_LMAP);
        return run;
    }

    private static ByteBuffer readBackInstances() {
        final InstanceRing ring = TesrBatchRenderer.INSTANCE.instanceRing();
        final ByteBuffer data = BufferUtils.createByteBuffer(ParticleInstancedAttribs.STRIDE);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, ring.bufferId());
        GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, data);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        return data;
    }

    /**
     * A particle the registry has no descriptor for, so the instancer runs its own
     * {@code renderParticle} into the capture buffer and decodes the result.
     */
    private static class CaptureParticle extends Particle {

        CaptureParticle() {
            super(null, 4.0D, 5.0D, 6.0D);
        }

        @Override
        public int getBrightnessForRender(float partialTick) {
            return LIGHT;
        }

        @Override
        public void renderParticle(BufferBuilder buffer, Entity entityIn, float partialTicks, float rotationX, float rotationZ, float rotationYZ, float rotationXY, float rotationXZ) {
            // The five arguments arrive in the order ParticleManager passes them, which is the order
            // the corner formulas read them in.
            for (int corner = 0; corner < ParticleQuadMesh.VERTEX_COUNT; corner++) {
                final float a = ParticleQuadMesh.cornerA(corner) * HALF;
                final float b = ParticleQuadMesh.cornerB(corner) * HALF;
                buffer.pos(
                        4.0D + ParticleQuadMesh.offsetX(a, b, rotationX, rotationXY),
                        5.0D + ParticleQuadMesh.offsetY(b, rotationZ),
                        6.0D + ParticleQuadMesh.offsetZ(a, b, rotationYZ, rotationXZ))
                    .tex(a < 0.0F ? 0.75F : 0.25F, b < 0.0F ? 0.5F : 0.125F)
                    .color(0x10, 0x20, 0x40, 0x80)
                    .lightmap((LIGHT >> 16) & 0xFFFF, LIGHT & 0xFFFF)
                    .endVertex();
            }
        }
    }

    /** Captured like any other particle, but leaves blend disabled behind it. */
    private static final class BlendDisablingParticle extends CaptureParticle {
        @Override
        public void renderParticle(BufferBuilder buffer, Entity entityIn, float partialTicks, float rotationX,
                                   float rotationZ, float rotationYZ, float rotationXY, float rotationXZ) {
            super.renderParticle(buffer, entityIn, partialTicks, rotationX, rotationZ, rotationYZ, rotationXY, rotationXZ);
            GLSMEnable.glDisable(GL11.GL_BLEND);
        }
    }
}
