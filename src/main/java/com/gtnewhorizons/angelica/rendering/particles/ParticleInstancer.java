package com.gtnewhorizons.angelica.rendering.particles;

import com.dhj.actinium.config.ActiniumRuntimeOptions;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizons.angelica.glsm.GLSMAttribStack;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.StateSet;
import com.gtnewhorizons.angelica.glsm.ffp.FfpExtendedAttribs;
import com.gtnewhorizons.angelica.glsm.ffp.Instancing;
import com.gtnewhorizons.angelica.glsm.ffp.ParticleInstancedAttribs;
import com.gtnewhorizons.angelica.glsm.ffp.ParticleQuadMesh;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.hooks.ImmediateExtendedAttribHandler;
import com.gtnewhorizons.angelica.rendering.ParticleRunSplitter;
import com.gtnewhorizons.angelica.rendering.RenderFailures;
import com.gtnewhorizons.angelica.rendering.tesr.InstanceRing;
import com.gtnewhorizons.angelica.rendering.tesr.MeshBuffer;
import com.gtnewhorizons.angelica.rendering.tesr.TesrBatchRenderer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.coderbot.iris.Iris;
import net.coderbot.iris.layer.GbufferPrograms;
import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.entity.Entity;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAddress0;

/**
 * Turns a particle run into instanced quad draws.
 *
 * <p>Vanilla {@code ParticleManager.renderParticles} opens one buffer per layer and depth-mask
 * combination, lets every particle write four vertices, and draws the run once. This class sits
 * between the two ends: descriptors fill an instance payload without touching the buffer, and
 * particles without a descriptor write into {@link ParticleCaptureBuffer} so
 * {@link ParticleQuadDecoder} can turn their vertices into the same payload. Vertices the decoder
 * rejects are replayed into the run untouched, so a particle the batch cannot express still draws
 * exactly like vanilla.</p>
 *
 * <p>The instance groups are sorted by the blend, depth-mask and texture state sampled while the
 * particle was captured, and drawn when the run ends, with the same state re-applied. A particle
 * layer only opens for {@code renderParticles} runs: {@code renderLitParticles} has no
 * {@code begin}/{@code draw} pair in 1.12.2 and its particles keep going through vanilla.</p>
 */
public final class ParticleInstancer {

    private static final int STRIDE = ParticleInstancedAttribs.STRIDE;
    private static final int INITIAL_BYTES = 256 * STRIDE;
    private static final int DRAW_VERTEX_FLAGS = ParticleQuadMesh.VERTEX_FLAGS | VertexFlags.COLOR_BIT | VertexFlags.BRIGHTNESS_BIT;

    private static final ParticleCaptureBuffer CAPTURE = new ParticleCaptureBuffer();
    private static final ParticleParams SCRATCH = new ParticleParams();
    private static final ParticleRenderState STATE = new ParticleRenderState();
    private static final ObjectArrayList<Group> GROUP_POOL = new ObjectArrayList<>();
    private static int groupCount;
    private static int restoreDepth = -1;

    private static final class Group {
        final ParticleRenderState state = new ParticleRenderState();
        ByteBuffer list;
        boolean translucent;
    }

    private static boolean layerActive;
    private static DeferredWorldRenderingPipeline unavailableFor;
    private static DeferredWorldRenderingPipeline deferred;
    private static BufferBuilder run;

    /** Rotation of the current run, in the order {@link ParticleQuadMesh} expects. */
    private static float rotX;
    private static float rotXZ;
    private static float rotZ;
    private static float rotYZ;
    private static float rotXY;

    private static long direct;
    private static long captured;
    private static long spilled;
    private static long undecodable;
    private static long draws;

    private ParticleInstancer() {}

    public static long getDirect() {
        return direct;
    }

    public static long getCaptured() {
        return captured;
    }

    public static long getSpilled() {
        return spilled;
    }

    public static long getUndecodable() {
        return undecodable;
    }

    public static long getDraws() {
        return draws;
    }

    /**
     * Opens the layer of one {@code renderParticles} run. Nothing is collected when the host option
     * that owns the batch is off or when no fixed function program can be generated.
     */
    public static void beginLayer() {
        layerActive = false;
        groupCount = 0;
        rotX = 0.0f;
        rotXZ = 0.0f;
        rotZ = 0.0f;
        rotYZ = 0.0f;
        rotXY = 0.0f;
        deferred = null;
        if (!ActiniumRuntimeOptions.useDeferredParticleBatching()) return;

        deferred = pipeline();
        if (available()) {
            layerActive = true;
        } else {
            deferred = null;
        }
    }

    /**
     * Submits one particle, or leaves it to the caller.
     *
     * @param particle particle about to be drawn
     * @param runBuffer the buffer the particle would write into
     * @param entity camera entity supplied by the particle manager
     * @param partialTicks interpolation factor of the current frame
     * @param rotationX camera billboard rotation, first of the five values 1.12.2 passes
     * @param rotationXZ camera billboard rotation
     * @param rotationZ camera billboard rotation
     * @param rotationYZ camera billboard rotation
     * @param rotationXY camera billboard rotation
     * @return whether this call owns the particle: {@code true} means the caller must not run
     *         {@code renderParticle} itself, {@code false} means the particle was not touched
     */
    public static boolean renderParticle(Particle particle, BufferBuilder runBuffer, Entity entity, float partialTicks,
        float rotationX, float rotationXZ, float rotationZ, float rotationYZ, float rotationXY) {
        if (!layerActive) return false;

        run = runBuffer;
        rotX = rotationX;
        rotXZ = rotationXZ;
        rotZ = rotationZ;
        rotYZ = rotationYZ;
        rotXY = rotationXY;

        final ParticleDescriptor descriptor = ParticleDescriptorRegistry.forClass(particle.getClass());
        if (descriptor != ParticleDescriptorRegistry.CAPTURE) {
            STATE.sample();
            SCRATCH.brightness = particle.getBrightnessForRender(partialTicks);
            if (!descriptor.describe(particle, partialTicks, SCRATCH, STATE)) return true;

            emit(groupFor(STATE, currentTranslucent()));
            direct++;
            return true;
        }

        CAPTURE.begin();
        particle.renderParticle(CAPTURE.buffer(), entity, partialTicks, rotationX, rotationXZ, rotationZ, rotationYZ, rotationXY);
        // Sampled after the particle ran: a particle may change blend or depth state inside its own
        // renderParticle, and the group has to draw under what it actually produced.
        STATE.sample();

        if (CAPTURE.spilled()) {
            CAPTURE.replayInto(runBuffer);
            spilled++;
            return true;
        }
        if (CAPTURE.captured() == 0) return true;

        if (!ParticleQuadDecoder.decode(CAPTURE.raw(), rotationX, rotationXZ, rotationZ, rotationYZ, rotationXY, SCRATCH)) {
            CAPTURE.replayInto(runBuffer);
            undecodable++;
            return true;
        }

        emit(groupFor(STATE, currentTranslucent()));
        captured++;
        return true;
    }

    /**
     * Draws every group the run collected and restores the state the draws replaced. A run may end
     * with groups still pending when the batch became unusable mid-run; they are replayed as
     * vertices into the run buffer so the run's own draw still covers them.
     */
    public static void endLayer() {
        if (!layerActive) {
            return;
        }
        layerActive = false;
        Throwable failure = null;
        try {
            if (groupCount > 0) {
                restoreDepth = GLSMAttribStack.pushState(StateSet.BATCH);
                for (int i = 0; i < groupCount; i++) {
                    final Group group = GROUP_POOL.get(i);
                    if (!group.translucent) flush(group);
                }
                for (int i = 0; i < groupCount; i++) {
                    final Group group = GROUP_POOL.get(i);
                    if (group.translucent) flush(group);
                }
            }
        } catch (Throwable t) {
            failure = t;
        } finally {
            if (restoreDepth >= 0) {
                try {
                    GLSMAttribStack.popStateTo(restoreDepth);
                } catch (Throwable t) {
                    failure = RenderFailures.suppress(failure, t);
                } finally {
                    restoreDepth = -1;
                }
                if (deferred != null) {
                    try {
                        GbufferPrograms.setTranslucencyDeclaration(ParticleRunSplitter.currentRunTranslucent());
                    } catch (Throwable t) {
                        failure = RenderFailures.suppress(failure, t);
                    }
                }
            }
            for (int i = 0; i < groupCount; i++) {
                final ByteBuffer list = GROUP_POOL.get(i).list;
                if (list != null) list.clear();
            }
            groupCount = 0;
            deferred = null;
            run = null;
        }
        RenderFailures.rethrowWrapped(failure);
    }

    /** Drops every cached group and the quad mesh, on world unload or a shader pack change. */
    public static void clear() {
        layerActive = false;
        deferred = null;
        run = null;
        unavailableFor = null;
        groupCount = 0;
        for (int i = 0; i < GROUP_POOL.size(); i++) {
            GROUP_POOL.get(i).list = null;
        }
        GROUP_POOL.clear();
        ParticleQuadMesh.delete();
        ParticleDescriptorRegistry.clearCache();
    }

    private static boolean currentTranslucent() {
        return deferred != null && ParticleRunSplitter.currentRunTranslucent();
    }

    private static void emit(Group group) {
        final ByteBuffer list = MeshBuffer.ensureCapacity(group.list, STRIDE, true);
        group.list = list;
        ParticleInstancedAttribs.writeInstance(memAddress0(list) + list.position(),
            SCRATCH.centerX, SCRATCH.centerY, SCRATCH.centerZ, SCRATCH.half,
            SCRATCH.u0, SCRATCH.v0, SCRATCH.u1, SCRATCH.v1,
            SCRATCH.colorABGR, SCRATCH.brightness);
        list.position(list.position() + STRIDE);
    }

    private static Group groupFor(ParticleRenderState state, boolean translucent) {
        for (int i = 0; i < groupCount; i++) {
            final Group group = GROUP_POOL.get(i);
            if (group.translucent == translucent && group.state.matches(state)) return group;
        }
        if (groupCount == GROUP_POOL.size()) GROUP_POOL.add(new Group());
        final Group group = GROUP_POOL.get(groupCount++);
        group.state.set(state);
        group.translucent = translucent;
        if (group.list != null) group.list.clear();
        group.list = MeshBuffer.ensureCapacity(group.list, INITIAL_BYTES, true);
        return group;
    }

    private static boolean available() {
        if (deferred != null && deferred == unavailableFor) return false;
        if (!ShaderManager.getInstance().isEnabled()) return false;
        return deferred == null || deferred.supportsInstancing(Instancing.PARTICLE);
    }

    private static DeferredWorldRenderingPipeline pipeline() {
        if (!Iris.enabled) return null;
        return Iris.getPipelineManager().getPipelineNullable() instanceof DeferredWorldRenderingPipeline pipeline ? pipeline : null;
    }

    private static InstanceRing ring() {
        return TesrBatchRenderer.INSTANCE.instanceRing();
    }

    private static void flush(Group group) {
        final ByteBuffer list = group.list;
        final boolean listTranslucent = group.translucent;
        final int count = list.position() / STRIDE;
        if (count == 0) return;
        group.state.apply();
        list.flip();

        if (deferred != null) {
            GbufferPrograms.setTranslucencyDeclaration(listTranslucent);
            deferred.rebindCurrentPass();
            if (!deferred.hasInstancedVariant(Instancing.PARTICLE)) {
                unavailableFor = deferred;
                replayGroupAsVertices(group.state, list, count, run, rotX, rotXZ, rotZ, rotYZ, rotXY);
                list.clear();
                return;
            }
        }

        final long base = ring().upload(list, STRIDE);
        list.clear();

        if (deferred != null) {
            deferred.bindInstancedVariant(Instancing.PARTICLE);
            issueDraw(base, count, false);
            deferred.rebindCurrentPass();
        } else {
            issueDraw(base, count, true);
        }
    }

    private static void issueDraw(long base, int count, boolean ffp) {
        ParticleQuadMesh.update(rotX, rotXZ, rotZ, rotYZ, rotXY);
        final ImmediateExtendedAttribHandler extensionHandler = GLSMHooks.immediateExtendedHandler;
        if (extensionHandler != null && extensionHandler.wantsExtended()) {
            FfpExtendedAttribs.setNeutralCurrentValues();
        }
        GLSMVertexInput.glBindVertexArray(ParticleQuadMesh.vao());
        GLStateManager.ctx().vaos.setVertexFlags(DRAW_VERTEX_FLAGS);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, ring().bufferId());
        ParticleInstancedAttribs.pointInstanceAttribs(base);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        try {
            if (ffp) GLSMPrograms.setFfpInstancing(Instancing.PARTICLE);
            GLSMDraws.glDrawArraysInstanced(GL11.GL_QUADS, 0, ParticleQuadMesh.VERTEX_COUNT, count);
            draws++;
        } finally {
            GLSMPrograms.setFfpInstancing(Instancing.NONE);
            GLSMVertexInput.glBindVertexArray(0);
        }
    }

    /**
     * Writes a group back into the run as plain vertices, for the case where the shader pipeline has
     * no particle variant. The corners and texture coordinates are the ones the instance payload
     * keeps, so the result is the quad vanilla would have drawn.
     */
    static void replayGroupAsVertices(ParticleRenderState state, ByteBuffer list, int count, BufferBuilder run,
                                      float rotX, float rotXZ, float rotZ, float rotYZ, float rotXY) {
        state.apply();
        for (int i = 0; i < count; i++) {
            final int base = i * STRIDE;
            final float cx = list.getFloat(base);
            final float cy = list.getFloat(base + 4);
            final float cz = list.getFloat(base + 8);
            final float half = list.getFloat(base + 12);
            final float u0 = list.getFloat(base + ParticleInstancedAttribs.OFFSET_UV);
            final float v0 = list.getFloat(base + ParticleInstancedAttribs.OFFSET_UV + 4);
            final float u1 = list.getFloat(base + ParticleInstancedAttribs.OFFSET_UV + 8);
            final float v1 = list.getFloat(base + ParticleInstancedAttribs.OFFSET_UV + 12);
            final int color = list.getInt(base + ParticleInstancedAttribs.OFFSET_COLOR);
            final int light = (int) list.getFloat(base + ParticleInstancedAttribs.OFFSET_LIGHTMAP)
                | ((int) list.getFloat(base + ParticleInstancedAttribs.OFFSET_LIGHTMAP + 4) << 16);

            for (int corner = 0; corner < ParticleQuadMesh.VERTEX_COUNT; corner++) {
                final float a = ParticleQuadMesh.cornerA(corner);
                final float b = ParticleQuadMesh.cornerB(corner);
                final float ah = a * half;
                final float bh = b * half;
                run.pos(cx + ParticleQuadMesh.offsetX(ah, bh, rotX, rotYZ),
                        cy + ParticleQuadMesh.offsetY(bh, rotXZ),
                        cz + ParticleQuadMesh.offsetZ(ah, bh, rotZ, rotXY))
                    .tex(a < 0.0f ? u0 : u1, b < 0.0f ? v0 : v1)
                    .color(color & 255, (color >> 8) & 255, (color >> 16) & 255, (color >>> 24) & 255)
                    .lightmap((light >> 16) & 0xFFFF, light & 0xFFFF)
                    .endVertex();
            }
        }
    }
}
