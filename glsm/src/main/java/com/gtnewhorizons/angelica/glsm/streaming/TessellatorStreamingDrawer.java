package com.gtnewhorizons.angelica.glsm.streaming;

import com.gtnewhorizon.gtnhlib.client.renderer.DirectTessellator;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.DefaultVertexFormat;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormat;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.ITessellatorData;
import com.gtnewhorizons.angelica.glsm.QuadConverter;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.debug.GLSMDebug;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAddress0;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAlloc;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memFree;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

/**
 * Replaces the vanilla Tessellator's FFP client-array draw path with a streaming VBO+VAO approach for GL 3.3 core profile compatibility.
 * <p>
 * Uses a persistent-mapped ring buffer on GL4.4+ hardware (zero-copy uploads with fence sync),
 * falling back to the classic orphan pattern on older hardware or on overflow.
 * Maintains two VAO sets (persistent + orphan) per vertex format (up to 16 combinations).
 */
public class TessellatorStreamingDrawer {

    private static final Logger LOGGER = LogManager.getLogger("TessellatorStreamingDrawer");
    private static final int FORMAT_COUNT = VertexFlags.BITSET_SIZE; // 16
    private static final int INITIAL_REPACK_CAPACITY = 0x10000;
    private static final int RAW_VERTEX_STRIDE_INTS = 8;
    private static final boolean DEBUG_STREAMING_DRAWS = Boolean.getBoolean("actinium.glsm.verboseDrawLogs");

    private static PersistentStreamingBuffer persistentBuffer;
    private static final OrphanStreamingBuffer[] orphanBuffers = new OrphanStreamingBuffer[FORMAT_COUNT];

    private static final int[] persistentVAOs = new int[FORMAT_COUNT];
    private static final int[] orphanVAOs = new int[FORMAT_COUNT];
    // Owning thread per VAO slot. VAOs are context-local objects: during the splash screen the
    // splash thread owns a separate GL context, and its numeric ids collide with the main
    // context's namespace. A slot must only be glDeleteVertexArrays'd on the thread that
    // created it — deleting it under another context destroys an unrelated live VAO there
    // (issue #150: the splash-finish destroy deleted the BufferBuilder streaming VAO).
    private static final Thread[] persistentVaoOwners = new Thread[FORMAT_COUNT];
    private static final Thread[] orphanVaoOwners = new Thread[FORMAT_COUNT];

    private static ByteBuffer repackBuffer;
    private static IntBuffer repackIntBuffer;
    private static long repackAddress;
    private static int repackCapacity;

    private static boolean initialized = false;

    static {
        // Initial repack buffer: 64KB
        repackCapacity = INITIAL_REPACK_CAPACITY;
        repackBuffer = memAlloc(repackCapacity);
        repackAddress = memAddress0(repackBuffer);
        repackIntBuffer = repackBuffer.order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    private static void init() {
        if (initialized) return;
        initialized = true;

        if (RenderSystem.supportsBufferStorage()
            && !Boolean.getBoolean("angelica.forceOrphanStreaming")
            && !Boolean.getBoolean("actinium.glsm.forceOrphanStreaming")) {
            try {
                persistentBuffer = new PersistentStreamingBuffer();
                LOGGER.info("Persistent streaming buffer created ({}MB)", PersistentStreamingBuffer.DEFAULT_CAPACITY / (1024 * 1024));
            } catch (Exception e) {
                LOGGER.warn("Failed to create persistent streaming buffer, using orphan fallback", e);
                persistentBuffer = null;
            }
        }
    }

    /**
     * Draw the vanilla Tessellator's data using streaming VBO+VAO instead of FFP client arrays.
     */
    public static int draw(ITessellatorData tess) {
        final boolean perfDebugEnabled = GLSMPerfDebug.isEnabled();
        final long perfStart = perfDebugEnabled ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.STREAM_DRAW) : 0L;
        if (!tess.isDrawing()) {
            throw new IllegalStateException("Not tesselating!");
        }

        tess.setDrawing(false);

        final int vertexCount = tess.getVertexCount();
        if (vertexCount == 0) {
            final int result = tess.getRawBufferIndex() * 4;
            tess.angelica$reset();
            if (perfDebugEnabled) {
                GLSMPerfDebug.end(GLSMPerfDebug.Stage.STREAM_DRAW, perfStart);
            }
            return result;
        }

        // Determine the optimal vertex format from the tessellator's flags
        final int flags = VertexFlags.convertToFlags(tess.hasTexture(), tess.hasColor(), tess.hasNormals(), tess.hasBrightness());
        final VertexFormat format = DefaultVertexFormat.ALL_FORMATS[flags];
        final int vertexSize = format.getVertexSize();

        final int requiredBytes = vertexCount * vertexSize;
        ensureRepackCapacity(requiredBytes);

        final int[] rawBuffer = tess.getRawBuffer();
        final int rawBufferIndex = tess.getRawBufferIndex();
        final int packedBytes = packRawVertices(repackIntBuffer, rawBuffer, rawBufferIndex, flags);
        final long writePtr;
        if (packedBytes >= 0) {
            writePtr = repackAddress + packedBytes;
        } else {
            writePtr = format.writeToBuffer0(repackAddress, rawBuffer, rawBufferIndex);
        }
        repackBuffer.position(0);
        repackBuffer.limit((int)(writePtr - repackAddress));

        final boolean singleQuadFastPath = isSingleQuadFastPath(
            tess.getDrawMode(), vertexCount, flags, rawBuffer, rawBufferIndex);
        uploadAndDraw(
            repackBuffer,
            flags,
            format,
            vertexSize,
            tess.getDrawMode(),
            vertexCount,
            singleQuadFastPath);
        if (perfDebugEnabled) {
            GLSMPerfDebug.count(GLSMPerfDebug.Source.STREAM_TESSELLATOR);
        }

        // Shrink rawBuffer if oversized
        if (tess.getRawBufferSize() > 0x20000 && tess.getRawBufferIndex() < (tess.getRawBufferSize() << 3)) {
            tess.setRawBufferSize(0x10000);
            tess.setRawBuffer(new int[tess.getRawBufferSize()]);
        }

        final int result = tess.getRawBufferIndex() * 4;
        tess.angelica$reset();
        if (perfDebugEnabled) {
            GLSMPerfDebug.end(GLSMPerfDebug.Stage.STREAM_DRAW, perfStart);
        }
        return result;
    }

    /**
     * Draw DirectTessellator data via streaming VBO+VAO. Used for live immediate mode emulation.
     */
    public static void drawDirect(DirectTessellator dt) {
        final boolean perfDebugEnabled = GLSMPerfDebug.isEnabled();
        final long perfStart = perfDebugEnabled ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.STREAM_DRAW_DIRECT) : 0L;
        final VertexFormat format = dt.getVertexFormat();
        if (format == null) {
            if (perfDebugEnabled) {
                GLSMPerfDebug.end(GLSMPerfDebug.Stage.STREAM_DRAW_DIRECT, perfStart);
            }
            return;
        }

        final int vertexCount = dt.getVertexCount();
        if (vertexCount == 0) {
            if (perfDebugEnabled) {
                GLSMPerfDebug.end(GLSMPerfDebug.Stage.STREAM_DRAW_DIRECT, perfStart);
            }
            return;
        }

        final int drawMode = dt.getDrawMode();
        final int flags = format.getVertexFlags();
        final ByteBuffer buffer = dt.getWriteBuffer();
        final int vertexSize = format.getVertexSize();

        uploadAndDraw(buffer, flags, format, vertexSize, drawMode, vertexCount, false);
        if (perfDebugEnabled) {
            GLSMPerfDebug.count(GLSMPerfDebug.Source.DIRECT_EXTERNAL);
        }
        if (perfDebugEnabled) {
            GLSMPerfDebug.end(GLSMPerfDebug.Stage.STREAM_DRAW_DIRECT, perfStart);
        }
    }

    /**
     * Upload pre-packed vertex data and draw. Public API for external batch systems.
     * @param packedData  buffer positioned at 0 with limit set to total bytes
     * @param drawMode    GL draw mode (GL_QUADS, GL_TRIANGLES, etc.)
     * @param flags       vertex format flags (from VertexFlags)
     * @param vertexCount number of vertices
     */
    public static void drawPacked(ByteBuffer packedData, int drawMode, int flags, int vertexCount) {
        final VertexFormat format = DefaultVertexFormat.ALL_FORMATS[flags];
        final int vertexSize = format.getVertexSize();
        uploadAndDraw(packedData, flags, format, vertexSize, drawMode, vertexCount, false);
    }

    private static String cachedDebugInfo = "Stream: not initialized";
    private static long lastDebugUpdateNanos;
    private static final long DEBUG_UPDATE_INTERVAL_NS = 500_000_000L; // 500ms

    public static String getDebugInfo() {
        if (!initialized) return "Stream: not initialized";

        final long now = System.nanoTime();
        if (now - lastDebugUpdateNanos < DEBUG_UPDATE_INTERVAL_NS) {
            return cachedDebugInfo;
        }
        lastDebugUpdateNanos = now;

        int orphanCount = 0;
        int orphanBytes = 0;
        for (int i = 0; i < FORMAT_COUNT; i++) {
            if (orphanBuffers[i] != null) {
                orphanCount++;
                orphanBytes += orphanBuffers[i].getCapacity();
            }
        }

        if (persistentBuffer != null) {
            cachedDebugInfo = String.format("Stream: Persistent %s (%s free) + %d orphan (%s)",
                formatBytes(persistentBuffer.getCapacity()), formatBytes(persistentBuffer.getRemaining()),
                orphanCount, formatBytes(orphanBytes));
        } else {
            cachedDebugInfo = String.format("Stream: Orphan (%d bufs, %s)", orphanCount, formatBytes(orphanBytes));
        }
        return cachedDebugInfo;
    }

    private static String formatBytes(int bytes) {
        if (bytes >= 1024 * 1024) return String.format("%5.1fMB", bytes / (1024.0 * 1024.0));
        if (bytes >= 1024) return String.format("%5.1fKB", bytes / 1024.0);
        return String.format("%5dB", bytes);
    }

    public static void endFrame() {
        if (persistentBuffer != null) {
            persistentBuffer.postDraw();
        }
    }

    /**
     * Upload packed vertex data to a streaming buffer and issue the draw call.
     * Tries the persistent ring buffer first, falls back to orphan buffer on overflow.
     */
    private static void uploadAndDraw(
        ByteBuffer packed,
        int flags,
        VertexFormat format,
        int vertexSize,
        int drawMode,
        int vertexCount,
        boolean singleQuadFastPath
    ) {
        final boolean perfDebugEnabled = GLSMPerfDebug.isEnabled();
        final long perfStart = perfDebugEnabled ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.STREAM_UPLOAD_AND_DRAW) : 0L;
        final boolean perfSampled = perfStart != 0L;
        ensureVAO(flags, format);

        int firstVertex = -1;

        if (persistentBuffer != null) {
            final long uploadStart = perfSampled ? GLSMPerfDebug.now() : 0L;
            firstVertex = persistentBuffer.upload(packed, vertexSize);
            if (perfSampled) {
                GLSMPerfDebug.record(GLSMPerfDebug.Stage.STREAM_PERSISTENT_UPLOAD, uploadStart, GLSMPerfDebug.now());
            }
        }

        final boolean persistentPath = firstVertex >= 0;
        final int vao;
        final int bufferId;
        if (persistentPath) {
            vao = persistentVAOs[flags];
            bufferId = persistentBuffer.getBufferId();
            GLStateManager.glBindVertexArray(persistentVAOs[flags]);
        } else {
            vao = orphanVAOs[flags];
            bufferId = orphanBuffers[flags].getBufferId();
            GLStateManager.glBindVertexArray(orphanVAOs[flags]);
            final long uploadStart = perfSampled ? GLSMPerfDebug.now() : 0L;
            orphanBuffers[flags].upload(packed);
            if (perfSampled) {
                GLSMPerfDebug.record(GLSMPerfDebug.Stage.STREAM_ORPHAN_UPLOAD, uploadStart, GLSMPerfDebug.now());
            }
            firstVertex = 0;
        }

        if (DEBUG_STREAMING_DRAWS) {
            GLSMDebug.logStreamingDraw(
                drawMode,
                flags,
                vertexSize,
                vertexCount,
                vertexCount * vertexSize,
                firstVertex,
                persistentPath,
                vao,
                bufferId,
            packed);
        }
        GLStateManager.prepareWideLineEmulation(drawMode);
        if (DEBUG_STREAMING_DRAWS) {
            final int activeProgram = GLStateManager.getActiveProgram();
            if (activeProgram != 0) {
                GLSMDebug.logDrawOnActiveProgram("stream", drawMode, flags, vertexCount, activeProgram);
            }
        }
        final long preDrawStart = perfSampled ? GLSMPerfDebug.now() : 0L;
        ShaderManager.getInstance().preDraw(flags);
        if (perfSampled) {
            GLSMPerfDebug.record(GLSMPerfDebug.Stage.STREAM_SHADER_PREDRAW, preDrawStart, GLSMPerfDebug.now());
        }
        final long drawStart = perfSampled ? GLSMPerfDebug.now() : 0L;
        drawWithQuadConversion(drawMode, firstVertex, vertexCount, singleQuadFastPath);
        if (perfSampled) {
            GLSMPerfDebug.record(GLSMPerfDebug.Stage.STREAM_DRAW_CALL, drawStart, GLSMPerfDebug.now());
        }
        GLStateManager.glBindVertexArray(0);
        ShaderManager.getInstance().clearClientVertexFlags();
        if (perfDebugEnabled) {
            GLSMPerfDebug.end(GLSMPerfDebug.Stage.STREAM_UPLOAD_AND_DRAW, perfStart);
        }
    }

    private static void drawWithQuadConversion(int drawMode, int firstVertex, int vertexCount, boolean singleQuadFastPath) {
        if (drawMode == GL11.GL_QUADS) {
            if (singleQuadFastPath) {
                RENDER_BACKEND.drawArrays(GL11.GL_TRIANGLE_FAN, firstVertex, 4);
            } else {
                QuadConverter.drawQuadsAsTriangles(firstVertex, vertexCount);
            }
        } else if (drawMode == GL11.GL_QUAD_STRIP) {
            RENDER_BACKEND.drawArrays(GL11.GL_TRIANGLE_STRIP, firstVertex, vertexCount & ~1);
        } else if (drawMode == GL11.GL_POLYGON) {
            RENDER_BACKEND.drawArrays(GL11.GL_TRIANGLE_FAN, firstVertex, vertexCount);
        } else {
            RENDER_BACKEND.drawArrays(drawMode, firstVertex, vertexCount);
        }
    }

    /**
     * Ensure the repack buffer is large enough for the given byte count.
     * Public for use by external batch systems that need to pack data before calling {@link #drawPacked}.
     */
    public static void ensureRepackCapacity(int requiredBytes) {
        if (repackBuffer != null && requiredBytes <= repackCapacity) return;

        int newCapacity = nextRepackCapacity(repackCapacity, requiredBytes);

        if (repackBuffer != null) {
            memFree(repackBuffer);
        }
        repackBuffer = memAlloc(newCapacity);
        repackAddress = memAddress0(repackBuffer);
        repackIntBuffer = repackBuffer.order(ByteOrder.nativeOrder()).asIntBuffer();
        repackCapacity = newCapacity;
    }

    /**
     * Packs the common legacy raw layouts without invoking one writer for every attribute.
     * Returns {@code -1} when the format contains an attribute that still needs the generic writer.
     */
    static int packRawVertices(IntBuffer destination, int[] rawBuffer, int rawBufferIndex, int flags) {
        if (!supportsRawPacking(flags)) {
            return -1;
        }

        destination.clear();
        if (flags == VertexFlags.COLOR_BIT) {
            for (int index = 0; index < rawBufferIndex; index += RAW_VERTEX_STRIDE_INTS) {
                destination.put(rawBuffer, index, 3);
                destination.put(rawBuffer[index + 5]);
            }
        } else {
            final int copiedInts = flags == (VertexFlags.TEXTURE_BIT | VertexFlags.COLOR_BIT) ? 6
                : flags == VertexFlags.TEXTURE_BIT ? 5 : 3;
            for (int index = 0; index < rawBufferIndex; index += RAW_VERTEX_STRIDE_INTS) {
                destination.put(rawBuffer, index, copiedInts);
            }
        }
        return destination.position() * Integer.BYTES;
    }

    static boolean supportsRawPacking(int flags) {
        return flags == 0
            || flags == VertexFlags.TEXTURE_BIT
            || flags == VertexFlags.COLOR_BIT
            || flags == (VertexFlags.TEXTURE_BIT | VertexFlags.COLOR_BIT);
    }

    /**
     * A single rectangular quad can use a non-indexed fan and avoid the shared quad EBO.
     * Flat-sensitive attributes must be constant so the provoking vertex remains equivalent.
     */
    static boolean isSingleQuadFastPath(int drawMode, int vertexCount, int flags, int[] rawBuffer, int rawBufferIndex) {
        if (drawMode != GL11.GL_QUADS || vertexCount != 4 || rawBufferIndex != RAW_VERTEX_STRIDE_INTS * 4) {
            return false;
        }
        if (!isParallelogram(rawBuffer, 0)) {
            return false;
        }
        if ((flags & VertexFlags.TEXTURE_BIT) != 0 && !isParallelogram(rawBuffer, 3)) {
            return false;
        }
        if ((flags & VertexFlags.COLOR_BIT) != 0 && !hasConstantAttribute(rawBuffer, 5)) {
            return false;
        }
        if ((flags & VertexFlags.NORMAL_BIT) != 0 && !hasConstantAttribute(rawBuffer, 6)) {
            return false;
        }
        return (flags & VertexFlags.BRIGHTNESS_BIT) == 0 || hasConstantAttribute(rawBuffer, 7);
    }

    private static boolean hasConstantAttribute(int[] rawBuffer, int attributeOffset) {
        final int first = rawBuffer[attributeOffset];
        return rawBuffer[RAW_VERTEX_STRIDE_INTS + attributeOffset] == first
            && rawBuffer[RAW_VERTEX_STRIDE_INTS * 2 + attributeOffset] == first
            && rawBuffer[RAW_VERTEX_STRIDE_INTS * 3 + attributeOffset] == first;
    }

    private static boolean isParallelogram(int[] rawBuffer, int attributeOffset) {
        for (int component = 0; component < (attributeOffset == 0 ? 3 : 2); component++) {
            final float first = Float.intBitsToFloat(rawBuffer[attributeOffset + component]);
            final float second = Float.intBitsToFloat(rawBuffer[RAW_VERTEX_STRIDE_INTS + attributeOffset + component]);
            final float third = Float.intBitsToFloat(rawBuffer[RAW_VERTEX_STRIDE_INTS * 2 + attributeOffset + component]);
            final float fourth = Float.intBitsToFloat(rawBuffer[RAW_VERTEX_STRIDE_INTS * 3 + attributeOffset + component]);
            if (first + third != second + fourth) {
                return false;
            }
        }
        return true;
    }

    /** Calculates the next power-of-two repack capacity, including after a full drawer destroy. */
    static int nextRepackCapacity(int currentCapacity, int requiredBytes) {
        if (requiredBytes <= currentCapacity) return currentCapacity;

        int newCapacity = Math.max(INITIAL_REPACK_CAPACITY, currentCapacity);
        while (newCapacity < requiredBytes) {
            newCapacity *= 2;
        }
        return newCapacity;
    }

    /** Get the repack buffer's native address. Valid until next {@link #ensureRepackCapacity} call. */
    public static long getRepackAddress() {
        return repackAddress;
    }

    /** Get the repack ByteBuffer. Caller must set position/limit before passing to {@link #drawPacked}. */
    public static ByteBuffer getRepackBuffer() {
        return repackBuffer;
    }

    private static void ensureVAO(int flags, VertexFormat format) {
        init();

        if (orphanVAOs[flags] == 0) {
            orphanBuffers[flags] = new OrphanStreamingBuffer();

            orphanVAOs[flags] = GLStateManager.glGenVertexArrays();
            orphanVaoOwners[flags] = Thread.currentThread();
            GLStateManager.glBindVertexArray(orphanVAOs[flags]);
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, orphanBuffers[flags].getBufferId());
            format.setupBufferState(0L);
            QuadConverter.attachSharedEboToCurrentVao();
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLStateManager.glBindVertexArray(0);
        }

        if (persistentBuffer != null && persistentVAOs[flags] == 0) {
            persistentVAOs[flags] = GLStateManager.glGenVertexArrays();
            persistentVaoOwners[flags] = Thread.currentThread();
            GLStateManager.glBindVertexArray(persistentVAOs[flags]);
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, persistentBuffer.getBufferId());
            format.setupBufferState(0L);
            QuadConverter.attachSharedEboToCurrentVao();
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLStateManager.glBindVertexArray(0);
        }
    }

    /**
     * Clean up all VAOs, streaming buffers, and the repack buffer.
     */
    public static void destroy() {
        final Thread current = Thread.currentThread();
        for (int i = 0; i < FORMAT_COUNT; i++) {
            // Only delete a VAO on the thread that created it. A slot owned by another thread
            // lives in that thread's GL context (the splash screen's), which is already dead by
            // the time destroy() runs elsewhere — the driver has reclaimed the object, and the
            // recycled numeric id may now name a live VAO in the current context (issue #150).
            if (persistentVAOs[i] != 0) {
                if (persistentVaoOwners[i] == current) {
                    GLStateManager.glDeleteVertexArrays(persistentVAOs[i]);
                }
                persistentVAOs[i] = 0;
                persistentVaoOwners[i] = null;
            }
            if (orphanVAOs[i] != 0) {
                if (orphanVaoOwners[i] == current) {
                    GLStateManager.glDeleteVertexArrays(orphanVAOs[i]);
                }
                orphanVAOs[i] = 0;
                orphanVaoOwners[i] = null;
            }
            // Buffers are shared across contexts, so they survive the splash context's death
            // and must always be deleted explicitly.
            if (orphanBuffers[i] != null) { orphanBuffers[i].destroy(); orphanBuffers[i] = null; }
        }
        if (persistentBuffer != null) {
            persistentBuffer.destroy();
            persistentBuffer = null;
        }
        if (repackBuffer != null) {
            memFree(repackBuffer);
            repackBuffer = null;
            repackIntBuffer = null;
            repackAddress = 0;
            repackCapacity = 0;
        }
        initialized = false;
    }
}
