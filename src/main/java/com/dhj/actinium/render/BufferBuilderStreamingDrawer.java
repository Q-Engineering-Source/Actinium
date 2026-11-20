package com.dhj.actinium.render;

import com.dhj.actinium.config.ActiniumRuntimeOptions;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.debug.GLSMDebug;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.hooks.ImmediateExtendedAttribHandler;
import com.gtnewhorizons.angelica.glsm.streaming.OrphanStreamingBuffer;
import com.gtnewhorizons.angelica.glsm.streaming.TessellatorEntityIds;
import com.gtnewhorizons.angelica.glsm.streaming.PersistentStreamingBuffer;
import com.gtnewhorizons.angelica.glsm.streaming.StreamingDrawOptions;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import dhj.embeddedt.embeddium.api.debug.RenderDebugHooksHolder;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.nio.ByteBuffer;
import java.util.Map;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAddress0;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAlloc;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memCopy;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memFree;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;

public final class BufferBuilderStreamingDrawer {
    private static final Logger LOGGER = LogManager.getLogger("BufferBuilderStreamingDrawer");
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("actinium.bufferBuilderStreaming", "true"));

    private static final Map<VertexFormat, DrawState> DRAW_STATES = new Object2ObjectOpenHashMap<>();
    private static PersistentStreamingBuffer persistentBuffer;
    private static boolean initialized;

    private static ByteBuffer extScratch;
    private static long extScratchAddress;
    private static int extScratchCapacity;

    private static OrphanStreamingBuffer entityIdBuffer;
    private static ByteBuffer entityIdScratch;
    private static long entityIdScratchAddress;
    private static int entityIdScratchCapacity;

    private BufferBuilderStreamingDrawer() {
    }

    public static boolean isEnabled() {
        return ENABLED && ActiniumRuntimeOptions.allowDirectMemoryAccess();
    }

    public static void draw(BufferBuilder bufferBuilder, String debugSource) {
        final boolean perfDebugEnabled = GLSMPerfDebug.isEnabled();
        final long perfStart = perfDebugEnabled ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.BUFFERBUILDER_STREAM_DRAW) : 0L;
        if (bufferBuilder.getVertexCount() <= 0) {
            bufferBuilder.reset();
            if (perfDebugEnabled) {
                GLSMPerfDebug.end(GLSMPerfDebug.Stage.BUFFERBUILDER_STREAM_DRAW, perfStart);
            }
            return;
        }

        VertexFormat format = bufferBuilder.getVertexFormat();
        int vertexCount = bufferBuilder.getVertexCount();
        int drawMode = bufferBuilder.getDrawMode();
        if (perfDebugEnabled) {
            GLSMPerfDebug.countBufferBuilder(debugSource, drawMode, vertexCount);
        }
        // drawRaw already creates the bounded view and leaves the source buffer untouched.
        drawRaw(bufferBuilder.getByteBuffer(), format, vertexCount, drawMode, debugSource,
            TessellatorEntityIds.runsFor(bufferBuilder, vertexCount));
        TessellatorEntityIds.onReset(bufferBuilder);
        bufferBuilder.reset();
        if (perfDebugEnabled) {
            GLSMPerfDebug.end(GLSMPerfDebug.Stage.BUFFERBUILDER_STREAM_DRAW, perfStart);
        }
    }

    public static void drawRaw(ByteBuffer buffer, VertexFormat format, int vertexCount, int drawMode, String debugSource) {
        drawRaw(buffer, format, vertexCount, drawMode, debugSource, 0);
    }

    private static void drawRaw(ByteBuffer buffer, VertexFormat format, int vertexCount, int drawMode, String debugSource,
                                int idRuns) {
        if (vertexCount <= 0) {
            return;
        }

        init();

        final boolean perfDebugEnabled = GLSMPerfDebug.isEnabled();

        DrawState state = ensureDrawState(format);
        int stride = format.getSize();
        int byteCount = vertexCount * stride;
        ByteBuffer upload = buffer.duplicate();
        upload.position(0);
        upload.limit(byteCount);

        final ImmediateExtendedAttribHandler extHandler = GLSMHooks.immediateExtendedHandler;
        final int extPrim = extHandler == null ? 0 : ImmediateExtendedAttribHandler.extPrimVerts(drawMode, vertexCount);
        final int texOffset = extPrim == 0 ? -1 : elementOffset(format, VertexFormatElement.EnumUsage.UV);
        final int normalOffset = extPrim == 0 ? -1 : elementOffset(format, VertexFormatElement.EnumUsage.NORMAL);
        final boolean doExt = extPrim != 0 && texOffset >= 0 && extHandler.wantsExtended();
        if (doExt) {
            final int baseSize = stride;
            stride = baseSize + ImmediateExtendedAttribHandler.EXT_STRIDE;
            byteCount = vertexCount * stride;
            ensureExtScratch(byteCount);
            interleaveBase(memAddress0(upload) + upload.position(), extScratchAddress, vertexCount, baseSize, stride);
            extHandler.buildPacked(memAddress0(upload) + upload.position(), baseSize, 0, texOffset, normalOffset,
                vertexCount, extPrim, extScratchAddress + baseSize, stride);
            extScratch.position(0);
            extScratch.limit(byteCount);
            upload = extScratch;
        }

        int savedVao = GLStateManager.getBoundVAO();
        int savedVbo = GLStateManager.getBoundVBO();
        int firstVertex = -1;
        boolean restoreArrayBuffer = false;
        boolean logDrawDiagnostics = GLSMDebug.shouldLogDrawDiagnostics();
        boolean checkDrawErrors = RenderDebugHooksHolder.shouldCaptureGlState();
        String formatDescription = logDrawDiagnostics || checkDrawErrors ? format.toString() : null;

        try {
            if (persistentBuffer != null) {
                final long uploadStart = perfDebugEnabled ? GLSMPerfDebug.now() : 0L;
                // Quad conversion indexes the shared EBO from firstVertex / 4, so a QUADS upload must start
                // on a quad boundary. Every other draw mode addresses vertices directly and needs no padding.
                firstVertex = persistentBuffer.upload(upload, stride, drawMode == GL11.GL_QUADS ? 4 : 1);
                if (perfDebugEnabled && firstVertex >= 0) {
                    GLSMPerfDebug.record(GLSMPerfDebug.Stage.BUFFERBUILDER_PERSISTENT_UPLOAD, uploadStart, GLSMPerfDebug.now());
                }
            }

            final DrawPath drawPath = DrawPath.fromFirstVertex(firstVertex);
            if (drawPath == DrawPath.ORPHAN && state.orphanBuffer == null) {
                restoreArrayBuffer = true;
                state.orphanBuffer = new OrphanStreamingBuffer();
                state.orphanVao = VanillaVertexBufferRenderer.createStreamingVertexArray(format, state.orphanBuffer.getBufferId());
            }
            final int persistentVbo = drawPath == DrawPath.PERSISTENT ? persistentBuffer.getBufferId() : 0;
            if (doExt) {
                if (drawPath == DrawPath.PERSISTENT) {
                    if (state.extendedPersistentVao == 0) {
                        state.extendedPersistentVao = VanillaVertexBufferRenderer.createExtendedStreamingVertexArray(
                            format, persistentVbo, stride);
                    }
                } else if (state.extendedOrphanVao == 0) {
                    state.extendedOrphanVao = VanillaVertexBufferRenderer.createExtendedStreamingVertexArray(
                        format, state.orphanBuffer.getBufferId(), stride);
                }
            }
            final int vao = doExt
                ? drawPath.select(state.extendedPersistentVao, state.extendedOrphanVao)
                : drawPath.select(state.persistentVao, state.orphanVao);
            final int vbo = drawPath == DrawPath.PERSISTENT ? persistentVbo : state.orphanBuffer.getBufferId();
            // Force the real VAO binding even when the GLStateManager cache already matches.
            // Native code outside this drawer can change the actual binding without updating the cache.
            final long initialVaoBindStart = perfDebugEnabled
                ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.BUFFERBUILDER_INITIAL_VAO_BIND) : 0L;
            try {
                GLSMVertexInput.glBindVertexArray(vao);
            } finally {
                if (perfDebugEnabled) {
                    GLSMPerfDebug.end(GLSMPerfDebug.Stage.BUFFERBUILDER_INITIAL_VAO_BIND, initialVaoBindStart);
                }
            }
            if (drawPath.changesArrayBufferBinding()) {
                restoreArrayBuffer = true;
                GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
                final long uploadStart = perfDebugEnabled ? GLSMPerfDebug.now() : 0L;
                state.orphanBuffer.upload(upload);
                if (perfDebugEnabled) {
                    GLSMPerfDebug.record(GLSMPerfDebug.Stage.BUFFERBUILDER_ORPHAN_UPLOAD, uploadStart, GLSMPerfDebug.now());
                }
                firstVertex = 0;
            }

            if (logDrawDiagnostics) {
                GLSMDebug.logBufferBuilderUpload(formatDescription, drawMode, state.vertexFlags, stride, vertexCount, byteCount, vao, vbo);
            }

            final long prepareDrawStart = perfDebugEnabled
                ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.BUFFERBUILDER_PREPARE_DRAW) : 0L;
            try {
                GLStateManager.prepareWideLineEmulation(drawMode);
                if (!StreamingDrawOptions.isExternalProgramPreserved()) {
                    ShaderManager.getInstance().preDraw(state.vertexFlags);
                }
            } finally {
                if (perfDebugEnabled) {
                    GLSMPerfDebug.end(GLSMPerfDebug.Stage.BUFFERBUILDER_PREPARE_DRAW, prepareDrawStart);
                }
            }
            if (checkDrawErrors) {
                final long checkDrawErrorStart = perfDebugEnabled
                    ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.BUFFERBUILDER_CHECK_DRAW_ERRORS) : 0L;
                try {
                    RenderDebugHooksHolder.checkDrawError("bufferbuilder-stream:after-predraw", debugSource, drawMode, state.vertexFlags, stride, vertexCount, formatDescription, vao, vbo);
                } finally {
                    if (perfDebugEnabled) {
                        GLSMPerfDebug.end(GLSMPerfDebug.Stage.BUFFERBUILDER_CHECK_DRAW_ERRORS, checkDrawErrorStart);
                    }
                }
            }
            final boolean entityIdsBound = prepareEntityIds(idRuns, vertexCount);
            final long drawStart = perfDebugEnabled ? GLSMPerfDebug.now() : 0L;
            VanillaVertexBufferRenderer.drawArrays(drawMode, firstVertex, vertexCount);
            if (entityIdsBound) {
                GLSMVertexInput.glDisableVertexAttribArray(ImmediateExtendedAttribHandler.LOC_ENTITY_IDS);
            }
            if (perfDebugEnabled) {
                GLSMPerfDebug.record(GLSMPerfDebug.Stage.BUFFERBUILDER_DRAW_CALL, drawStart, GLSMPerfDebug.now());
            }
            if (checkDrawErrors) {
                final long checkDrawErrorStart = perfDebugEnabled
                    ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.BUFFERBUILDER_CHECK_DRAW_ERRORS) : 0L;
                try {
                    RenderDebugHooksHolder.checkDrawError("bufferbuilder-stream:after-draw", debugSource, drawMode, state.vertexFlags, stride, vertexCount, formatDescription, vao, vbo);
                } finally {
                    if (perfDebugEnabled) {
                        GLSMPerfDebug.end(GLSMPerfDebug.Stage.BUFFERBUILDER_CHECK_DRAW_ERRORS, checkDrawErrorStart);
                    }
                }
            }
        } finally {
            final long restoreBindingsStart = perfDebugEnabled
                ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.BUFFERBUILDER_RESTORE_BINDINGS) : 0L;
            try {
                GLSMVertexInput.glBindVertexArray(savedVao);
                if (restoreArrayBuffer) {
                    GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedVbo);
                }
            } finally {
                if (perfDebugEnabled) {
                    GLSMPerfDebug.end(GLSMPerfDebug.Stage.BUFFERBUILDER_RESTORE_BINDINGS, restoreBindingsStart);
                }
            }
            if (checkDrawErrors) {
                final long checkDrawErrorStart = perfDebugEnabled
                    ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.BUFFERBUILDER_CHECK_DRAW_ERRORS) : 0L;
                try {
                    RenderDebugHooksHolder.checkDrawError("bufferbuilder-stream:after-restore", debugSource, drawMode, state.vertexFlags, stride, vertexCount, formatDescription, savedVao, savedVbo);
                } finally {
                    if (perfDebugEnabled) {
                        GLSMPerfDebug.end(GLSMPerfDebug.Stage.BUFFERBUILDER_CHECK_DRAW_ERRORS, checkDrawErrorStart);
                    }
                }
            }
        }
    }

    public static void endFrame() {
        if (persistentBuffer != null) {
            persistentBuffer.endFrame();
        }
    }

    public static void destroy() {
        for (DrawState state : DRAW_STATES.values()) {
            if (state.persistentVao != 0) {
                GLSMVertexInput.glDeleteVertexArrays(state.persistentVao);
            }
            if (state.orphanVao != 0) {
                GLSMVertexInput.glDeleteVertexArrays(state.orphanVao);
            }
            if (state.orphanBuffer != null) {
                state.orphanBuffer.destroy();
            }
        }
        DRAW_STATES.clear();

        if (persistentBuffer != null) {
            persistentBuffer.destroy();
            persistentBuffer = null;
        }
        initialized = false;
    }

    /**
     * Recreates the per-format VAOs on the current context. Splash replacements can migrate the
     * game to a different GL context when the splash finishes (issue #150); VAOs from the
     * previous context are invalid afterward, while the streaming VBOs are shared and stay valid.
     */
    public static void recreateVertexArrays() {
        if (!initialized) {
            return;
        }
        for (Map.Entry<VertexFormat, DrawState> entry : DRAW_STATES.entrySet()) {
            final VertexFormat format = entry.getKey();
            final DrawState state = entry.getValue();
            if (state.orphanVao != 0 && !VanillaVertexBufferRenderer.vaoMatchesCurrentContext(state.orphanVao)) {
                state.orphanVao = VanillaVertexBufferRenderer.createStreamingVertexArray(format, state.orphanBuffer.getBufferId());
            }
            if (state.persistentVao != 0 && !VanillaVertexBufferRenderer.vaoMatchesCurrentContext(state.persistentVao)) {
                state.persistentVao = VanillaVertexBufferRenderer.createStreamingVertexArray(format, persistentBuffer.getBufferId());
            }
        }
    }

    private static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        persistentBuffer = PersistentStreamingBuffer.createOrNull(PersistentStreamingBuffer.DEFAULT_CAPACITY,
            GLSMPerfDebug.FenceSource.BUFFER_BUILDER);
        if (persistentBuffer != null) {
            LOGGER.info("Persistent BufferBuilder streaming buffer created ({}MB)", PersistentStreamingBuffer.DEFAULT_CAPACITY / (1024 * 1024));
        }
    }


    /**
     * Uploads the packed entity, block entity and item ids of this batch and points the id attribute at them.
     * Only batches that cross an entity boundary need the per-vertex attribute; a single run is covered by the
     * constant value ShaderManager writes before the draw.
     *
     * @return whether the attribute was bound, so the caller can disable it again
     */


    /** @return byte offset of the first element with the given usage, or {@code -1} when the format has none */
    private static int elementOffset(VertexFormat format, VertexFormatElement.EnumUsage usage) {
        for (int i = 0; i < format.getElementCount(); i++) {
            if (format.getElement(i).getUsage() == usage) return format.getOffset(i);
        }
        return -1;
    }

    private static void interleaveBase(long src, long dst, int vertexCount, int vertexSize, int combinedStride) {
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            memCopy(src + (long) vertex * vertexSize, dst + (long) vertex * combinedStride, vertexSize);
        }
    }

    private static void ensureExtScratch(int requiredBytes) {
        if (extScratch != null && requiredBytes <= extScratchCapacity) return;

        int newCapacity = Math.max(0x4000, extScratchCapacity);
        while (newCapacity < requiredBytes) {
            newCapacity *= 2;
        }
        if (extScratch != null) memFree(extScratch);
        extScratch = memAlloc(newCapacity);
        extScratchAddress = memAddress0(extScratch);
        extScratchCapacity = newCapacity;
    }

    private static boolean prepareEntityIds(int idRuns, int vertexCount) {
        if (idRuns <= 1) return false;
        final ImmediateExtendedAttribHandler handler = GLSMHooks.immediateExtendedHandler;
        if (handler == null || !handler.wantsEntityIds()) return false;

        final int stride = ImmediateExtendedAttribHandler.ENTITY_IDS_STRIDE;
        final int bytes = vertexCount * stride;
        ensureEntityIdScratch(bytes);
        TessellatorEntityIds.write(entityIdScratchAddress, stride, vertexCount, idRuns);
        entityIdScratch.position(0);
        entityIdScratch.limit(bytes);

        if (entityIdBuffer == null) entityIdBuffer = new OrphanStreamingBuffer();
        entityIdBuffer.upload(entityIdScratch);

        final int savedVbo = GLStateManager.getBoundVBO();
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, entityIdBuffer.getBufferId());
        ImmediateExtendedAttribHandler.setupEntityIdsPointer(0L, stride);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedVbo);
        return true;
    }

    private static void ensureEntityIdScratch(int requiredBytes) {
        if (entityIdScratch != null && requiredBytes <= entityIdScratchCapacity) return;

        int newCapacity = Math.max(0x4000, entityIdScratchCapacity);
        while (newCapacity < requiredBytes) {
            newCapacity *= 2;
        }
        if (entityIdScratch != null) memFree(entityIdScratch);
        entityIdScratch = memAlloc(newCapacity);
        entityIdScratchAddress = memAddress0(entityIdScratch);
        entityIdScratchCapacity = newCapacity;
    }

    private static DrawState ensureDrawState(VertexFormat format) {
        DrawState state = DRAW_STATES.get(format);
        if (state != null) {
            return state;
        }

        state = new DrawState();
        state.vertexFlags = VanillaVertexBufferRenderer.vertexFlags(format);
        if (persistentBuffer != null) {
            state.persistentVao = VanillaVertexBufferRenderer.createStreamingVertexArray(format, persistentBuffer.getBufferId());
        }

        DRAW_STATES.put(format, state);
        return state;
    }

    private static final class DrawState {
        private int vertexFlags;
        private int orphanVao;
        private int persistentVao;
        private int extendedOrphanVao;
        private int extendedPersistentVao;
        private OrphanStreamingBuffer orphanBuffer;
    }

    /** Selects GL objects and binding restoration according to the completed upload path. */
    enum DrawPath {
        PERSISTENT(false),
        ORPHAN(true);

        private final boolean changesArrayBufferBinding;

        DrawPath(boolean changesArrayBufferBinding) {
            this.changesArrayBufferBinding = changesArrayBufferBinding;
        }

        static DrawPath fromFirstVertex(int firstVertex) {
            return firstVertex >= 0 ? PERSISTENT : ORPHAN;
        }

        int select(int persistentObject, int orphanObject) {
            return this == PERSISTENT ? persistentObject : orphanObject;
        }

        boolean changesArrayBufferBinding() {
            return this.changesArrayBufferBinding;
        }
    }
}
