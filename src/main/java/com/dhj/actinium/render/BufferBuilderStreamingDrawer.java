package com.dhj.actinium.render;

import com.dhj.actinium.config.ActiniumRuntimeOptions;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.debug.GLSMDebug;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.gtnewhorizons.angelica.glsm.streaming.OrphanStreamingBuffer;
import com.gtnewhorizons.angelica.glsm.streaming.PersistentStreamingBuffer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import dhj.embeddedt.embeddium.api.debug.RenderDebugHooksHolder;
import org.lwjgl.opengl.GL15;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

import java.nio.ByteBuffer;
import java.util.Map;

public final class BufferBuilderStreamingDrawer {
    private static final Logger LOGGER = LogManager.getLogger("BufferBuilderStreamingDrawer");
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("actinium.bufferBuilderStreaming", "true"));

    private static final Map<VertexFormat, DrawState> DRAW_STATES = new Object2ObjectOpenHashMap<>();
    private static PersistentStreamingBuffer persistentBuffer;
    private static boolean initialized;

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
        drawRaw(bufferBuilder.getByteBuffer(), format, vertexCount, drawMode, debugSource);
        bufferBuilder.reset();
        if (perfDebugEnabled) {
            GLSMPerfDebug.end(GLSMPerfDebug.Stage.BUFFERBUILDER_STREAM_DRAW, perfStart);
        }
    }

    public static void drawRaw(ByteBuffer buffer, VertexFormat format, int vertexCount, int drawMode, String debugSource) {
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
                firstVertex = persistentBuffer.upload(upload, stride);
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
            final int vao = drawPath.select(state.persistentVao, state.orphanVao);
            final int vbo = drawPath == DrawPath.PERSISTENT ? persistentVbo : state.orphanBuffer.getBufferId();
            // Force the real VAO binding even when the GLStateManager cache already matches.
            // Native code outside this drawer can change the actual binding without updating the cache.
            GLStateManager.glBindVertexArray(vao);
            if (drawPath.changesArrayBufferBinding()) {
                restoreArrayBuffer = true;
                GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
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

            GLStateManager.prepareWideLineEmulation(drawMode);
            ShaderManager.getInstance().preDraw(state.vertexFlags);
            if (checkDrawErrors) {
                RenderDebugHooksHolder.checkDrawError("bufferbuilder-stream:after-predraw", debugSource, drawMode, state.vertexFlags, stride, vertexCount, formatDescription, vao, vbo);
            }
            final long drawStart = perfDebugEnabled ? GLSMPerfDebug.now() : 0L;
            VanillaVertexBufferRenderer.drawArrays(drawMode, firstVertex, vertexCount);
            if (perfDebugEnabled) {
                GLSMPerfDebug.record(GLSMPerfDebug.Stage.BUFFERBUILDER_DRAW_CALL, drawStart, GLSMPerfDebug.now());
            }
            if (checkDrawErrors) {
                RenderDebugHooksHolder.checkDrawError("bufferbuilder-stream:after-draw", debugSource, drawMode, state.vertexFlags, stride, vertexCount, formatDescription, vao, vbo);
            }
        } finally {
            GLStateManager.glBindVertexArray(savedVao);
            if (restoreArrayBuffer) {
                GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedVbo);
            }
            if (checkDrawErrors) {
                RenderDebugHooksHolder.checkDrawError("bufferbuilder-stream:after-restore", debugSource, drawMode, state.vertexFlags, stride, vertexCount, formatDescription, savedVao, savedVbo);
            }
        }
    }

    public static void endFrame() {
        if (persistentBuffer != null) {
            persistentBuffer.postDraw();
        }
    }

    public static void destroy() {
        for (DrawState state : DRAW_STATES.values()) {
            if (state.persistentVao != 0) {
                GLStateManager.glDeleteVertexArrays(state.persistentVao);
            }
            if (state.orphanVao != 0) {
                GLStateManager.glDeleteVertexArrays(state.orphanVao);
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

        if (RenderSystem.supportsBufferStorage()
            && !Boolean.getBoolean("angelica.forceOrphanStreaming")
            && !Boolean.getBoolean("actinium.glsm.forceOrphanStreaming")) {
            try {
                persistentBuffer = new PersistentStreamingBuffer();
                LOGGER.info("Persistent BufferBuilder streaming buffer created ({}MB)", PersistentStreamingBuffer.DEFAULT_CAPACITY / (1024 * 1024));
            } catch (Exception e) {
                LOGGER.warn("Failed to create persistent BufferBuilder streaming buffer, using orphan fallback", e);
                persistentBuffer = null;
            }
        }
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
