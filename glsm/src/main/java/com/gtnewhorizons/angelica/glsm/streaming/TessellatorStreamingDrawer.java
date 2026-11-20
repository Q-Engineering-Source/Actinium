package com.gtnewhorizons.angelica.glsm.streaming;

import com.gtnewhorizon.gtnhlib.client.renderer.DirectTessellator;
import com.gtnewhorizon.gtnhlib.client.renderer.cel.util.ModelQuadUtil;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.DefaultVertexFormat;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormat;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLContextState;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.ITessellatorData;
import com.gtnewhorizons.angelica.glsm.QuadConverter;
import com.gtnewhorizons.angelica.glsm.debug.GLSMDebug;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.gtnewhorizons.angelica.glsm.ffp.FfpExtendedAttribs;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMConfig;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.hooks.ImmediateExtendedAttribHandler;
import com.gtnewhorizons.angelica.glsm.hooks.LineDrawHandler;
import com.gtnewhorizons.angelica.glsm.states.Color4;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAddress0;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAlloc;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memCopy;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memFree;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memPutFloat;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memPutInt;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memPutLong;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;

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

    private static ByteBuffer extScratch;
    private static long extScratchAddress;
    private static int extScratchCapacity;


    private static ByteBuffer repackBuffer;
    private static IntBuffer repackIntBuffer;
    private static long repackAddress;
    private static int repackCapacity;

    private static ByteBuffer lineQuadBuffer;
    private static long lineQuadAddress;
    private static int lineQuadCapacity;

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

        persistentBuffer = PersistentStreamingBuffer.createOrNull(PersistentStreamingBuffer.DEFAULT_CAPACITY,
            GLSMPerfDebug.FenceSource.TESSELLATOR);
        if (persistentBuffer != null) {
            LOGGER.info("Persistent streaming buffer created ({}MB)", PersistentStreamingBuffer.DEFAULT_CAPACITY / (1024 * 1024));
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

        // A quad draw without normals still needs one per face when the active program reads them; the handler
        // derives it from the four corners instead of the constant vanilla fills in.
        final ImmediateExtendedAttribHandler normalHandler = GLSMHooks.immediateExtendedHandler;
        final boolean faceNormals = !tess.hasNormals() && normalHandler != null
            && ImmediateExtendedAttribHandler.extPrimVerts(tess.getDrawMode(), vertexCount) == 4
            && normalHandler.wantsFaceNormals();
        final boolean hasNormals = tess.hasNormals() || faceNormals;

        // Determine the optimal vertex format from the tessellator's flags
        GLStateManager.requireExitVertexDefaults(!hasNormals, !tess.hasTexture(), !tess.hasColor(), !tess.hasBrightness());
        final int flags = VertexFlags.convertToFlags(tess.hasTexture(), tess.hasColor(), hasNormals, tess.hasBrightness());
        final VertexFormat format = DefaultVertexFormat.ALL_FORMATS[flags];
        final int vertexSize = format.getVertexSize();

        final int requiredBytes = vertexCount * vertexSize;
        ensureRepackCapacity(requiredBytes);

        final int[] rawBuffer = tess.getRawBuffer();
        final int rawBufferIndex = tess.getRawBufferIndex();
        if (faceNormals) normalHandler.writeFaceNormals(rawBuffer, vertexCount);
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
        final int idRuns = TessellatorEntityIds.runsFor(tess, vertexCount);
        final GLContextState swappedCtx = idRuns >= 1 ? GLStateManager.ctx() : null;
        final long restoreIds = swappedCtx != null ? swapEntityIds(swappedCtx, TessellatorEntityIds.firstRunIds()) : 0L;
        try {
            if (!tryExtended(repackBuffer, flags, format, vertexSize, tess.getDrawMode(), vertexCount,
                    singleQuadFastPath, idRuns)) {
                uploadAndDraw(
                    repackBuffer,
                    flags,
                    format,
                    vertexSize,
                    tess.getDrawMode(),
                    vertexCount,
                    singleQuadFastPath);
            }
        } finally {
            if (swappedCtx != null) swapEntityIds(swappedCtx, restoreIds);
        }
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
        TessellatorEntityIds.onReset(tess);
        if (perfDebugEnabled) {
            GLSMPerfDebug.end(GLSMPerfDebug.Stage.STREAM_DRAW, perfStart);
        }
        return result;
    }

    /**
     * Materialises the attributes the caller never supplied, so every vertex in the packed buffer
     * carries the full streaming layout.
     *
     * <p>Batched entity and TESR draws read {@link ITessellatorData#getRawBuffer()} directly and
     * merge vertices into shared buffers, so a missing colour, lightmap, normal or texture
     * coordinate has to be filled from the current GL state before the buffer is queued rather
     * than at draw time.</p>
     */
    public static void fillMissingAttributes(ITessellatorData tess) {
        final int vertexCount = tess.getVertexCount();
        final int[] rawBuffer = tess.getRawBuffer();
        if (rawBuffer == null || rawBuffer.length < vertexCount * ModelQuadUtil.VERTEX_SIZE) {
            throw new IllegalStateException("Packed vertex buffer does not hold " + vertexCount
                + " vertices in the shared " + ModelQuadUtil.VERTEX_SIZE + "-int layout");
        }

        final int defaultColor;
        if (!tess.hasColor()) {
            final Color4 c = GLSMCurrentAttribs.getColor();
            defaultColor = ((int) (Color4.clamp01(c.getAlpha()) * 255) << 24) | ((int) (Color4.clamp01(c.getBlue()) * 255) << 16)
                | ((int) (Color4.clamp01(c.getGreen()) * 255) << 8) | (int) (Color4.clamp01(c.getRed()) * 255);
        } else {
            defaultColor = 0;
        }

        final int defaultBrightness = tess.hasBrightness() ? 0 : GLSMConfig.packedLastBrightness();

        final ImmediateExtendedAttribHandler normalHandler = GLSMHooks.immediateExtendedHandler;
        final boolean faceNormals = !tess.hasNormals() && normalHandler != null
            && ImmediateExtendedAttribHandler.extPrimVerts(tess.getDrawMode(), vertexCount) == 4 && normalHandler.wantsFaceNormals();
        final boolean fillNormal = !tess.hasNormals() && !faceNormals;
        GLStateManager.requireExitVertexDefaults(fillNormal, !tess.hasTexture(), !tess.hasColor(), !tess.hasBrightness());

        final int defaultNormal;
        if (fillNormal) {
            final Vector3f n = ShaderManager.getCurrentNormal();
            defaultNormal = (packNormalComponent(n.z) << 16) | ((packNormalComponent(n.y) & 0xFF) << 8) | (packNormalComponent(n.x) & 0xFF);
        } else {
            defaultNormal = 0;
        }

        final int defaultTexU, defaultTexV;
        if (!tess.hasTexture()) {
            final Vector4f tc = ShaderManager.getCurrentTexCoord();
            defaultTexU = Float.floatToRawIntBits(tc.x);
            defaultTexV = Float.floatToRawIntBits(tc.y);
        } else {
            defaultTexU = 0;
            defaultTexV = 0;
        }

        for (int i = 0; i < vertexCount; i++) {
            final int base = i * ModelQuadUtil.VERTEX_SIZE;
            if (!tess.hasTexture()) {
                rawBuffer[base | ModelQuadUtil.TEX_X_INDEX] = defaultTexU;
                rawBuffer[base | ModelQuadUtil.TEX_Y_INDEX] = defaultTexV;
            }
            if (!tess.hasColor()) {
                rawBuffer[base | ModelQuadUtil.COLOR_INDEX] = defaultColor;
            }
            if (fillNormal) {
                rawBuffer[base | ModelQuadUtil.NORMAL_INDEX] = defaultNormal;
            }
            if (!tess.hasBrightness()) {
                rawBuffer[base | ModelQuadUtil.LIGHT_INDEX] = defaultBrightness;
            }
        }
        if (faceNormals) {
            normalHandler.writeFaceNormals(rawBuffer, vertexCount);
        }
    }

    private static int packNormalComponent(float value) {
        final float clamped = Math.max(-1.0f, Math.min(1.0f, value));
        return (int) (clamped * 127);
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

        if (!tryExtended(buffer, flags, format, vertexSize, drawMode, vertexCount, false, 0)) {
            uploadAndDraw(buffer, flags, format, vertexSize, drawMode, vertexCount, false);
        }
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
        if (!tryExtended(packedData, flags, format, vertexSize, drawMode, vertexCount, false, 0)) {
            uploadAndDraw(packedData, flags, format, vertexSize, drawMode, vertexCount, false);
        }
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
            persistentBuffer.endFrame();
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
        if (LineQuads.isLineMode(drawMode) && drawLinesAsQuads(packed, format, drawMode, vertexCount)) {
            if (perfDebugEnabled) {
                GLSMPerfDebug.end(GLSMPerfDebug.Stage.STREAM_UPLOAD_AND_DRAW, perfStart);
            }
            return;
        }
        final boolean perfSampled = perfStart != 0L;
        final StreamingVaos vaos = GLStateManager.getStreamingVaos();
        final boolean lineQuads = LineQuads.isQuadFormat(format);
        final int[] persistentVAOs = lineQuads ? vaos.lineQuadPersistentVAOs : vaos.persistentVAOs;
        final int[] orphanVAOs = lineQuads ? vaos.lineQuadOrphanVAOs : vaos.orphanVAOs;
        ensureVAO(persistentVAOs, orphanVAOs, flags, format);

        int firstVertex = -1;

        if (persistentBuffer != null) {
            final long uploadStart = perfSampled ? GLSMPerfDebug.now() : 0L;
            final int alignment = drawMode == GL11.GL_QUADS ? 4 : lineQuads ? LineQuads.VERTICES_PER_SEGMENT : 1;
            firstVertex = persistentBuffer.upload(packed, vertexSize, alignment);
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
            GLSMVertexInput.glBindVertexArray(vao);
        } else {
            vao = orphanVAOs[flags];
            bufferId = orphanBuffers[flags].getBufferId();
            GLSMVertexInput.glBindVertexArray(vao);
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
        if (!StreamingDrawOptions.isExternalProgramPreserved()) {
            ShaderManager.getInstance().preDraw(flags);
        }
        if (perfSampled) {
            GLSMPerfDebug.record(GLSMPerfDebug.Stage.STREAM_SHADER_PREDRAW, preDrawStart, GLSMPerfDebug.now());
        }
        final long drawStart = perfSampled ? GLSMPerfDebug.now() : 0L;
        drawWithQuadConversion(drawMode, firstVertex, vertexCount, singleQuadFastPath);
        if (perfSampled) {
            GLSMPerfDebug.record(GLSMPerfDebug.Stage.STREAM_DRAW_CALL, drawStart, GLSMPerfDebug.now());
        }
        GLSMVertexInput.glBindVertexArray(0);
        ShaderManager.getInstance().clearClientVertexFlags();
        if (perfDebugEnabled) {
            GLSMPerfDebug.end(GLSMPerfDebug.Stage.STREAM_UPLOAD_AND_DRAW, perfStart);
        }
    }

    /**
     * Expands a line draw into quads and streams them. The FFP path needs the line-quad vertex key, so the draw
     * runs with {@code lineQuadsActive} set; a host line program takes the direction-only layout instead.
     */
    private static boolean drawLinesAsQuads(ByteBuffer packed, VertexFormat format, int drawMode, int vertexCount) {
        final int segments = LineQuads.segmentCount(drawMode, vertexCount);
        if (segments == 0) {
            return false;
        }
        final LineDrawHandler handler = GLSMHooks.lineDrawHandler;
        if (handler != null && handler.bindLineProgram()) {
            try {
                drawLineQuads(packed, format, drawMode, vertexCount, segments, false);
            } finally {
                handler.restoreProgram();
            }
            return true;
        }
        if (GLSMDraws.ffpWidensLineQuads()) {
            final GLContextState glCtx = GLStateManager.ctx();
            glCtx.lineQuadsActive = true;
            try {
                drawLineQuads(packed, format, drawMode, vertexCount, segments, true);
            } finally {
                glCtx.lineQuadsActive = false;
            }
            return true;
        }
        return false;
    }

    private static void drawLineQuads(ByteBuffer packed, VertexFormat format, int drawMode, int vertexCount, int segments, boolean otherEnd) {
        final VertexFormat quadFormat = LineQuads.quadFormat(format);
        final int quadVertices = segments * LineQuads.VERTICES_PER_SEGMENT;
        final int quadBytes = quadVertices * quadFormat.getVertexSize();
        ensureLineQuadCapacity(quadBytes);
        final long src = memAddress0(packed) + packed.position();
        if (otherEnd) {
            LineQuads.expandWithOtherEnd(src, format, drawMode, vertexCount, lineQuadAddress);
        } else {
            LineQuads.expandWithDirection(src, format, drawMode, vertexCount, lineQuadAddress);
        }
        lineQuadBuffer.position(0);
        lineQuadBuffer.limit(quadBytes);
        final boolean culled = LineQuads.disableCulling();
        try {
            uploadAndDraw(lineQuadBuffer, quadFormat.getVertexFlags(), quadFormat, quadFormat.getVertexSize(), GL11.GL_TRIANGLES, quadVertices, false);
        } finally {
            LineQuads.restoreCulling(culled);
        }
        if (lineQuadCapacity > 0x20000 && quadBytes < (lineQuadCapacity >> 3)) {
            memFree(lineQuadBuffer);
            lineQuadBuffer = null;
            lineQuadCapacity = 0;
        }
    }

    private static void ensureLineQuadCapacity(int requiredBytes) {
        if (lineQuadBuffer != null && requiredBytes <= lineQuadCapacity) return;

        int newCapacity = Math.max(0x4000, lineQuadCapacity);
        while (newCapacity < requiredBytes) {
            newCapacity *= 2;
        }
        if (lineQuadBuffer != null) memFree(lineQuadBuffer);
        lineQuadBuffer = memAlloc(newCapacity);
        lineQuadAddress = memAddress0(lineQuadBuffer);
        lineQuadCapacity = newCapacity;
    }

    /**
     * Rebuilds {@code mc_midTexCoord} and {@code at_tangent} into a combined buffer when the current program reads
     * them, then draws it. Mirrors {@link #uploadAndDraw} with the extended attributes appended to every vertex, so
     * the draw does not have to go through the separate rebuild in {@link FfpExtendedAttribs}.
     *
     * @return whether the call drew, so the caller can skip the plain path
     */
    private static boolean tryExtended(ByteBuffer packed, int flags, VertexFormat format, int vertexSize,
                                       int drawMode, int vertexCount, boolean singleQuadFastPath, int idRuns) {
        final ImmediateExtendedAttribHandler extHandler = GLSMHooks.immediateExtendedHandler;
        if (extHandler == null) return false;
        if (LineQuads.isLineMode(drawMode) || LineQuads.isQuadFormat(format)) return false;

        final int extPrim = ImmediateExtendedAttribHandler.extPrimVerts(drawMode, vertexCount);
        final int texOffset = ImmediateExtendedAttribHandler.texOffset(format);
        final boolean doExt = extHandler.wantsExtended() && extPrim != 0 && format.hasTexture() && texOffset >= 0;
        final boolean doIds = extHandler.wantsEntityIds() && idRuns > 1;
        if (!doExt && !doIds) return false;

        final long srcBase = memAddress0(packed) + packed.position();
        final int extBase = vertexSize;
        final int idBase = vertexSize + ImmediateExtendedAttribHandler.EXT_STRIDE;
        final int combinedStride = idBase + ImmediateExtendedAttribHandler.ENTITY_IDS_STRIDE;
        final int combinedBytes = vertexCount * combinedStride;
        ensureExtScratch(combinedBytes);
        interleaveBase(srcBase, extScratchAddress, vertexCount, vertexSize, combinedStride, doExt, doIds);
        if (doExt) {
            final int normalOffset = ImmediateExtendedAttribHandler.normalOffset(format);
            extHandler.buildPacked(srcBase, vertexSize, 0, texOffset, normalOffset, vertexCount, extPrim,
                extScratchAddress + extBase, combinedStride);
        }
        if (doIds) {
            TessellatorEntityIds.write(extScratchAddress + idBase, combinedStride, vertexCount, idRuns);
        }
        extScratch.position(0);
        extScratch.limit(combinedBytes);
        uploadAndDrawExtended(extScratch, flags, format, combinedStride, drawMode, vertexCount, singleQuadFastPath);
        shrinkExtScratchIfOversized(combinedBytes);
        return true;
    }

    private static void interleaveBase(long src, long dst, int vertexCount, int vertexSize, int combinedStride,
                                       boolean fillExt, boolean fillIds) {
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            final long vertexDst = dst + (long) vertex * combinedStride;
            memCopy(src + (long) vertex * vertexSize, vertexDst, vertexSize);
        }
        if (!fillExt) writeNeutralExtended(dst, combinedStride, vertexCount, vertexSize);
        if (!fillIds) writeZeroIds(dst, combinedStride, vertexCount, vertexSize + ImmediateExtendedAttribHandler.EXT_STRIDE);
    }

    private static void writeNeutralExtended(long dst, int combinedStride, int vertexCount, int extOffset) {
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            final long extended = dst + (long) vertex * combinedStride + extOffset;
            memPutFloat(extended, 0.5f);
            memPutFloat(extended + 4, 0.5f);
            memPutInt(extended + 8, 0);
        }
    }

    private static void writeZeroIds(long dst, int combinedStride, int vertexCount, int idOffset) {
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            memPutLong(dst + (long) vertex * combinedStride + idOffset, 0L);
        }
    }

    /**
     * Swaps the recorded ids of a draw whose vertices carry several runs, and returns the previous value so the
     * caller can restore it after the draw.
     */
    private static long swapEntityIds(GLContextState glCtx, long ids) {
        final long previous = glCtx.entityIds;
        if (previous != ids) {
            glCtx.entityIds = ids;
            glCtx.dirtyEntityIdsAttrib = true;
        }
        return previous;
    }

    private static void uploadAndDrawExtended(ByteBuffer combined, int flags, VertexFormat format, int combinedStride,
                                              int drawMode, int vertexCount, boolean singleQuadFastPath) {
        final StreamingVaos vaos = GLStateManager.getStreamingVaos();
        ensureVAO(vaos.persistentVAOs, vaos.orphanVAOs, flags, format);
        ensureExtendedVAOs(vaos, flags, format);

        int firstVertex = -1;
        if (persistentBuffer != null) {
            final int alignment = drawMode == GL11.GL_QUADS ? 4 : 1;
            firstVertex = persistentBuffer.upload(combined, combinedStride, alignment);
        }

        if (firstVertex >= 0) {
            GLSMVertexInput.glBindVertexArray(vaos.extendedPersistentVAOs[flags]);
        } else {
            GLSMVertexInput.glBindVertexArray(vaos.extendedOrphanVAOs[flags]);
            orphanBuffers[flags].upload(combined);
            firstVertex = 0;
        }

        GLStateManager.prepareWideLineEmulation(drawMode);
        ShaderManager.getInstance().preDraw(flags);
        drawWithQuadConversion(drawMode, firstVertex, vertexCount, singleQuadFastPath);
        GLSMVertexInput.glBindVertexArray(0);
        ShaderManager.getInstance().clearClientVertexFlags();
    }

    private static void ensureExtendedVAOs(StreamingVaos vaos, int flags, VertexFormat format) {
        if (vaos.extendedOrphanVAOs[flags] == 0) {
            vaos.extendedOrphanVAOs[flags] = GLSMVertexInput.glGenVertexArrays();
            GLSMVertexInput.glBindVertexArray(vaos.extendedOrphanVAOs[flags]);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, orphanBuffers[flags].getBufferId());
            setupExtendedBufferState(format);
            QuadConverter.attachSharedEboToCurrentVao();
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLSMVertexInput.glBindVertexArray(0);
        }

        if (persistentBuffer != null && vaos.extendedPersistentVAOs[flags] == 0) {
            vaos.extendedPersistentVAOs[flags] = GLSMVertexInput.glGenVertexArrays();
            GLSMVertexInput.glBindVertexArray(vaos.extendedPersistentVAOs[flags]);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, persistentBuffer.getBufferId());
            setupExtendedBufferState(format);
            QuadConverter.attachSharedEboToCurrentVao();
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLSMVertexInput.glBindVertexArray(0);
        }
    }

    private static void setupExtendedBufferState(VertexFormat format) {
        final int combinedStride = format.getVertexSize() + ImmediateExtendedAttribHandler.EXT_STRIDE
            + ImmediateExtendedAttribHandler.ENTITY_IDS_STRIDE;
        long offset = 0;
        for (final VertexFormatElement element : format.getElements()) {
            element.setupBufferState(offset, combinedStride);
            offset += element.getByteSize();
        }
        ImmediateExtendedAttribHandler.setupExtAttribPointers(offset, combinedStride);
        ImmediateExtendedAttribHandler.setupEntityIdsPointer(offset + ImmediateExtendedAttribHandler.EXT_STRIDE, combinedStride);
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

    private static void shrinkExtScratchIfOversized(int lastRequiredBytes) {
        if (extScratchCapacity > 0x20000 && lastRequiredBytes < (extScratchCapacity >> 3)) {
            memFree(extScratch);
            extScratchCapacity = 0x4000;
            extScratch = memAlloc(extScratchCapacity);
            extScratchAddress = memAddress0(extScratch);
        }
    }

    private static void drawWithQuadConversion(int drawMode, int firstVertex, int vertexCount, boolean singleQuadFastPath) {
        GLSMDraws.drawCalls++;
        FfpExtendedAttribs.beginInternalDraw();
        try {
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
        } finally {
            FfpExtendedAttribs.endInternalDraw();
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

    private static void ensureVAO(int[] persistentVAOs, int[] orphanVAOs, int flags, VertexFormat format) {
        init();
        if (orphanBuffers[flags] == null) orphanBuffers[flags] = new OrphanStreamingBuffer();

        if (orphanVAOs[flags] == 0) {
            orphanVAOs[flags] = GLSMVertexInput.glGenVertexArrays();
            GLSMVertexInput.glBindVertexArray(orphanVAOs[flags]);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, orphanBuffers[flags].getBufferId());
            format.setupBufferState(0L);
            QuadConverter.attachSharedEboToCurrentVao();
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLSMVertexInput.glBindVertexArray(0);
        }

        if (persistentBuffer != null && persistentVAOs[flags] == 0) {
            persistentVAOs[flags] = GLSMVertexInput.glGenVertexArrays();
            GLSMVertexInput.glBindVertexArray(persistentVAOs[flags]);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, persistentBuffer.getBufferId());
            format.setupBufferState(0L);
            QuadConverter.attachSharedEboToCurrentVao();
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLSMVertexInput.glBindVertexArray(0);
        }
    }

    /** Delete live resources, or discard references after the old display context has died. */
    public static void destroy() {
        final StreamingVaos vaos = GLStateManager.getStreamingVaos();
        if (GLStateManager.displayContextMigrated()) {
            vaos.reset();
            return;
        }
        for (int i = 0; i < FORMAT_COUNT; i++) {
            if (vaos.persistentVAOs[i] != 0) {
                GLSMVertexInput.glDeleteVertexArrays(vaos.persistentVAOs[i]);
                vaos.persistentVAOs[i] = 0;
            }
            if (vaos.orphanVAOs[i] != 0) {
                GLSMVertexInput.glDeleteVertexArrays(vaos.orphanVAOs[i]);
                vaos.orphanVAOs[i] = 0;
            }
            if (vaos.extendedPersistentVAOs[i] != 0) {
                GLSMVertexInput.glDeleteVertexArrays(vaos.extendedPersistentVAOs[i]);
                vaos.extendedPersistentVAOs[i] = 0;
            }
            if (vaos.extendedOrphanVAOs[i] != 0) {
                GLSMVertexInput.glDeleteVertexArrays(vaos.extendedOrphanVAOs[i]);
                vaos.extendedOrphanVAOs[i] = 0;
            }
            if (orphanBuffers[i] != null) { orphanBuffers[i].destroy(); orphanBuffers[i] = null; }
        }
        if (extScratch != null) {
            memFree(extScratch);
            extScratch = null;
            extScratchAddress = 0;
            extScratchCapacity = 0;
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
