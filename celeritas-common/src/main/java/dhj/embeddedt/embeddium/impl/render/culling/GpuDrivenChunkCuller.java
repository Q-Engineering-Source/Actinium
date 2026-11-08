package dhj.embeddedt.embeddium.impl.render.culling;

import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import dhj.embeddedt.embeddium.impl.gl.shader.GlProgram;
import dhj.embeddedt.embeddium.impl.gl.shader.GlShader;
import dhj.embeddedt.embeddium.impl.gl.shader.ShaderConstants;
import dhj.embeddedt.embeddium.impl.gl.shader.ShaderType;
import dhj.embeddedt.embeddium.impl.render.shader.ShaderLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Owns the compute program that turns the visible list into indirect draw commands, and the buffers it reads.
 *
 * <p>The program reads three inputs: the visible-list entries a pass appended, the section metadata mirror, and the
 * frustum uniform block. It writes one draw command per visible facing run into the indirect buffer the draw call reads.
 * A dispatch batch binds all four resources once and restores whatever the caller had bound, because the terrain path
 * keeps its own buffers bound between passes and must not inherit the cull program's bindings.
 */
public final class GpuDrivenChunkCuller {
    private static final Logger LOG = LogManager.getLogger("ActiniumCulling");

    private static final String COMPUTE_SHADER_RESOURCE = "actinium:culling/chunk_cull.csh";

    /** Bytes of one visible-list entry: the section slot followed by the packed facing mask and output base. */
    public static final int VISIBLE_ENTRY_BYTES = 8;
    /** Bytes of one {@code DrawElementsIndirectCommand}: count, instance count, first index, base vertex, base instance. */
    public static final int INDIRECT_COMMAND_BYTES = 20;
    /** One indirect command per facing, so one entry can expand to at most this many draws. */
    public static final int FACINGS_PER_SECTION = 7;
    /** Threads per work group; must match {@code local_size_x} in the compute shader. */
    public static final int LOCAL_SIZE_X = 64;
    private static final int INITIAL_META_SECTIONS = 4096;

    static final int VISIBLE_SSBO_BINDING = 0;
    static final int META_SSBO_BINDING = 1;
    static final int INDIRECT_SSBO_BINDING = 2;
    static final int FRUSTUM_UBO_BINDING = 1;

    private boolean ready;
    private boolean disabled;

    private GlProgram<Void> program;
    private int metaSsboGlId;
    private int frustumUboGlId;

    private int metaCapacityBytes;

    private int savedProgram;
    private int savedVisibleSsbo;
    private int savedMetaSsbo;
    private int savedIndirectSsbo;
    private int savedFrustumUbo;

    /**
     * Loads the cull program and creates its buffers on first use.
     *
     * @return whether the compute path can be used on this context
     */
    public boolean ensureReady() {
        if (this.ready) {
            return true;
        }
        if (this.disabled) {
            return false;
        }
        if (!RenderSystem.supportsCompute()) {
            this.disabled = true;
            LOG.info("Compute-driven chunk culling disabled: compute shaders are unavailable");
            return false;
        }
        final GlShader computeShader = ShaderLoader.loadShader(ShaderType.COMPUTE, COMPUTE_SHADER_RESOURCE, ShaderConstants.EMPTY);
        try {
            this.program = GlProgram.builder("chunk_cull").attachShader(computeShader).link(context -> null);
        } finally {
            computeShader.delete();
        }
        this.metaSsboGlId = GLSMBuffers.glGenBuffers();
        this.frustumUboGlId = GLSMBuffers.glGenBuffers();

        GLSMBuffers.glBindBuffer(GL31.GL_UNIFORM_BUFFER, this.frustumUboGlId);
        GLSMBuffers.glBufferData(GL31.GL_UNIFORM_BUFFER, FrustumExtractor.UBO_SIZE_BYTES, GL15.GL_STREAM_DRAW);

        this.metaCapacityBytes = SectionMetaBuffer.BYTES_PER_SECTION * INITIAL_META_SECTIONS;
        GLSMBuffers.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.metaSsboGlId);
        GLSMBuffers.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, this.metaCapacityBytes, GL15.GL_DYNAMIC_DRAW);

        this.ready = true;
        return true;
    }

    /**
     * Uploads the whole section metadata mirror, growing the storage buffer when the mirror outgrew it.
     *
     * @return whether the upload happened
     */
    public boolean uploadSectionMeta(ByteBuffer metaBytes) {
        if (!this.ready || metaBytes == null || metaBytes.remaining() == 0) {
            return false;
        }
        final int needed = metaBytes.remaining();
        GLSMBuffers.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.metaSsboGlId);
        if (needed > this.metaCapacityBytes) {
            final int newCapacity = Math.max(needed,
                Math.max(this.metaCapacityBytes * 2, SectionMetaBuffer.BYTES_PER_SECTION * INITIAL_META_SECTIONS));
            GLSMBuffers.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, newCapacity, GL15.GL_DYNAMIC_DRAW);
            this.metaCapacityBytes = newCapacity;
        }
        GLSMBuffers.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, metaBytes);
        return true;
    }

    /**
     * Uploads the frustum uniform block a dispatch reads.
     */
    public void uploadFrustum(ByteBuffer frustumUboBytes) {
        if (!this.ready || frustumUboBytes == null) {
            return;
        }
        GLSMBuffers.glBindBuffer(GL31.GL_UNIFORM_BUFFER, this.frustumUboGlId);
        GLSMBuffers.glBufferSubData(GL31.GL_UNIFORM_BUFFER, 0L, frustumUboBytes);
    }

    /**
     * Binds the buffers and program a dispatch batch needs, saving what the caller had bound. Every call must be
     * paired with {@link #endDispatches()}.
     */
    void beginDispatches(int visibleSsboGlId, int indirectSsboGlId) {
        this.savedProgram = GLSMPrograms.getActiveProgram();
        this.savedVisibleSsbo = RenderSystem.getIndexedBufferBinding(GL43.GL_SHADER_STORAGE_BUFFER, VISIBLE_SSBO_BINDING);
        this.savedMetaSsbo = RenderSystem.getIndexedBufferBinding(GL43.GL_SHADER_STORAGE_BUFFER, META_SSBO_BINDING);
        this.savedIndirectSsbo = RenderSystem.getIndexedBufferBinding(GL43.GL_SHADER_STORAGE_BUFFER, INDIRECT_SSBO_BINDING);
        this.savedFrustumUbo = RenderSystem.getIndexedBufferBinding(GL31.GL_UNIFORM_BUFFER, FRUSTUM_UBO_BINDING);
        GLSMPrograms.glUseProgram(this.program.handle());
        RenderSystem.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, VISIBLE_SSBO_BINDING, visibleSsboGlId);
        RenderSystem.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, META_SSBO_BINDING, this.metaSsboGlId);
        RenderSystem.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, INDIRECT_SSBO_BINDING, indirectSsboGlId);
        RenderSystem.bindBufferBase(GL31.GL_UNIFORM_BUFFER, FRUSTUM_UBO_BINDING, this.frustumUboGlId);
    }

    /**
     * Dispatches one work group per {@link #LOCAL_SIZE_X} entries of the currently uploaded frustum block.
     */
    void dispatch(int visibleCount) {
        if (visibleCount <= 0) {
            return;
        }
        final int groups = (visibleCount + LOCAL_SIZE_X - 1) / LOCAL_SIZE_X;
        GLSMPrograms.glDispatchCompute(groups, 1, 1);
    }

    /**
     * Waits for the dispatch results and restores the bindings and program the caller had before
     * {@link #beginDispatches}.
     */
    void endDispatches() {
        GLSMBuffers.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT | GL42.GL_COMMAND_BARRIER_BIT);
        RenderSystem.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, VISIBLE_SSBO_BINDING, this.savedVisibleSsbo);
        RenderSystem.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, META_SSBO_BINDING, this.savedMetaSsbo);
        RenderSystem.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, INDIRECT_SSBO_BINDING, this.savedIndirectSsbo);
        RenderSystem.bindBufferBase(GL31.GL_UNIFORM_BUFFER, FRUSTUM_UBO_BINDING, this.savedFrustumUbo);
        GLSMPrograms.glUseProgram(this.savedProgram);
    }

    /**
     * Releases the program and buffers and keeps the instance out of the compute path afterwards.
     */
    public synchronized void shutdown() {
        if (this.program != null) {
            this.program.delete();
            this.program = null;
        }
        if (this.metaSsboGlId != 0) {
            GLSMBuffers.glDeleteBuffers(this.metaSsboGlId);
            this.metaSsboGlId = 0;
        }
        if (this.frustumUboGlId != 0) {
            GLSMBuffers.glDeleteBuffers(this.frustumUboGlId);
            this.frustumUboGlId = 0;
        }
        this.ready = false;
        this.disabled = true;
    }
}
