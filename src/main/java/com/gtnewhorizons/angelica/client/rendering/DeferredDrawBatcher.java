package com.gtnewhorizons.angelica.client.rendering;

import com.dhj.actinium.config.ActiniumRuntimeOptions;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import dhj.embeddedt.embeddium.api.shader.buffer.BufferBuilderExtension;
import com.dhj.actinium.render.VanillaBufferBuilderRenderer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAddress0;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAlloc;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memCopy;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memFree;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memGetByte;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memGetFloat;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memGetInt;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memPutByte;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memPutFloat;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memPutInt;

/**
 * 1.12 BufferBuilder port of Angelica's deferred particle batching path.
 *
 * <p>Angelica captures Tessellator raw buffers through DirectTessellator. On 1.12 the vanilla path already stores
 * vertex data in BufferBuilder's direct byte buffer, so this class keeps Angelica's state sorting, range metadata and
 * modelview delta pre-transform while copying 1.12 vertex bytes directly.</p>
 */
public final class DeferredDrawBatcher {
    private static final int INITIAL_BUFFER_SIZE = 64 * 1024;

    private static final List<DrawRange> RANGES = new ArrayList<>();
    private static final Matrix4f DEFAULT_MODELVIEW = new Matrix4f();
    private static final Matrix4f DEFAULT_MODELVIEW_INVERSE = new Matrix4f();
    private static final Matrix4f DELTA_MATRIX = new Matrix4f();
    private static final Vector3f SCRATCH = new Vector3f();

    private static ByteBuffer batchBuffer = memAlloc(INITIAL_BUFFER_SIZE);
    private static long batchAddress = memAddress0(batchBuffer);
    private static int batchCapacity = INITIAL_BUFFER_SIZE;
    private static int batchPosition;

    private static ByteBuffer mergeBuffer = memAlloc(INITIAL_BUFFER_SIZE);
    private static long mergeAddress = memAddress0(mergeBuffer);
    private static int mergeCapacity = INITIAL_BUFFER_SIZE;

    private static boolean active;
    private static boolean flushing;

    private DeferredDrawBatcher() {
    }

    public static boolean isActive() {
        return active;
    }

    public static void enter() {
        if (!ActiniumRuntimeOptions.useDeferredParticleBatching() || flushing || !ActiniumRuntimeOptions.allowDirectMemoryAccess()) {
            return;
        }

        active = true;
        batchPosition = 0;
        RANGES.clear();
        DEFAULT_MODELVIEW.set(GLStateManager.getModelViewMatrix());
        DEFAULT_MODELVIEW.invert(DEFAULT_MODELVIEW_INVERSE);
    }

    public static boolean capture(BufferBuilder bufferBuilder) {
        if (!active || flushing || !ActiniumRuntimeOptions.allowDirectMemoryAccess()) {
            return false;
        }

        finishIfNeeded(bufferBuilder);

        int vertexCount = bufferBuilder.getVertexCount();
        if (vertexCount <= 0) {
            bufferBuilder.reset();
            return true;
        }

        VertexFormat format = bufferBuilder.getVertexFormat();
        int drawMode = bufferBuilder.getDrawMode();
        int byteCount = vertexCount * format.getSize();
        ensureBatchCapacity(batchPosition + byteCount);

        ByteBuffer source = bufferBuilder.getByteBuffer().duplicate();
        source.position(0);
        source.limit(byteCount);

        long writeAddress = batchAddress + batchPosition;
        memCopy(memAddress0(source), writeAddress, byteCount);

        Matrix4fc transform = captureDeltaTransform();
        if (transform != null) {
            transformCopiedVertices(format, writeAddress, vertexCount, transform);
        }

        RANGES.add(new DrawRange(captureStateKey(), batchPosition, byteCount, format, vertexCount, drawMode));
        batchPosition += byteCount;
        bufferBuilder.reset();
        return true;
    }

    public static void exitAndFlush() {
        if (!active) {
            return;
        }

        active = false;
        if (RANGES.isEmpty()) {
            batchPosition = 0;
            return;
        }

        boolean depthTestEnabled = GLStateManager.getDepthTest().isEnabled();
        flushing = true;
        try {
            RANGES.sort(Comparator.comparingLong(DrawRange::stateKey));
            flushSorted(RANGES);
        } finally {
            RANGES.clear();
            batchPosition = 0;
            flushing = false;
            // Custom particles may be the only captured range and leave no trailing vanilla draw.
            applyDepthTest(depthTestEnabled);
        }
    }

    private static void finishIfNeeded(BufferBuilder bufferBuilder) {
        if (bufferBuilder instanceof BufferBuilderExtension extension) {
            if (extension.actinium$isDrawing()) {
                bufferBuilder.finishDrawing();
            }
            return;
        }

        bufferBuilder.finishDrawing();
    }

    private static Matrix4fc captureDeltaTransform() {
        Matrix4f current = GLStateManager.getModelViewMatrix();
        if (current.equals(DEFAULT_MODELVIEW)) {
            return null;
        }

        DEFAULT_MODELVIEW_INVERSE.mul(current, DELTA_MATRIX);
        return DELTA_MATRIX;
    }

    private static void transformCopiedVertices(VertexFormat format, long baseAddress, int vertexCount, Matrix4fc transform) {
        int stride = format.getSize();

        for (int elementIndex = 0; elementIndex < format.getElementCount(); elementIndex++) {
            VertexFormatElement element = format.getElement(elementIndex);
            int offset = format.getOffset(elementIndex);

            if (element.getUsage() == VertexFormatElement.EnumUsage.POSITION) {
                transformPositions(baseAddress, vertexCount, stride, offset, element, transform);
            } else if (element.getUsage() == VertexFormatElement.EnumUsage.NORMAL) {
                transformNormals(baseAddress, vertexCount, stride, offset, element, transform);
            }
        }
    }

    private static void transformPositions(
            long baseAddress,
            int vertexCount,
            int stride,
            int offset,
            VertexFormatElement element,
            Matrix4fc transform
    ) {
        if (element.getElementCount() < 3) {
            return;
        }

        for (int vertex = 0; vertex < vertexCount; vertex++) {
            long ptr = baseAddress + (long) vertex * stride + offset;
            if (!readPosition(ptr, element, SCRATCH)) {
                continue;
            }

            SCRATCH.mulPosition(transform);
            writePosition(ptr, element, SCRATCH);
        }
    }

    private static void transformNormals(
            long baseAddress,
            int vertexCount,
            int stride,
            int offset,
            VertexFormatElement element,
            Matrix4fc transform
    ) {
        if (element.getElementCount() < 3) {
            return;
        }

        for (int vertex = 0; vertex < vertexCount; vertex++) {
            long ptr = baseAddress + (long) vertex * stride + offset;
            if (!readNormal(ptr, element, SCRATCH)) {
                continue;
            }

            SCRATCH.mulDirection(transform).normalize();
            writeNormal(ptr, element, SCRATCH);
        }
    }

    private static boolean readPosition(long ptr, VertexFormatElement element, Vector3f out) {
        return switch (element.getType()) {
            case FLOAT -> {
                out.set(memGetFloat(ptr), memGetFloat(ptr + 4), memGetFloat(ptr + 8));
                yield true;
            }
            case INT, UINT -> {
                out.set(
                        Float.intBitsToFloat(memGetInt(ptr)),
                        Float.intBitsToFloat(memGetInt(ptr + 4)),
                        Float.intBitsToFloat(memGetInt(ptr + 8)));
                yield true;
            }
            default -> false;
        };
    }

    private static void writePosition(long ptr, VertexFormatElement element, Vector3f value) {
        switch (element.getType()) {
            case FLOAT -> {
                memPutFloat(ptr, value.x);
                memPutFloat(ptr + 4, value.y);
                memPutFloat(ptr + 8, value.z);
            }
            case INT, UINT -> {
                memPutInt(ptr, Float.floatToRawIntBits(value.x));
                memPutInt(ptr + 4, Float.floatToRawIntBits(value.y));
                memPutInt(ptr + 8, Float.floatToRawIntBits(value.z));
            }
            default -> {
            }
        }
    }

    private static boolean readNormal(long ptr, VertexFormatElement element, Vector3f out) {
        return switch (element.getType()) {
            case BYTE, UBYTE -> {
                out.set(memGetByte(ptr) / 127.0F, memGetByte(ptr + 1) / 127.0F, memGetByte(ptr + 2) / 127.0F);
                yield true;
            }
            case FLOAT -> {
                out.set(memGetFloat(ptr), memGetFloat(ptr + 4), memGetFloat(ptr + 8));
                yield true;
            }
            default -> false;
        };
    }

    private static void writeNormal(long ptr, VertexFormatElement element, Vector3f value) {
        switch (element.getType()) {
            case BYTE, UBYTE -> {
                memPutByte(ptr, (byte) (value.x * 127.0F));
                memPutByte(ptr + 1, (byte) (value.y * 127.0F));
                memPutByte(ptr + 2, (byte) (value.z * 127.0F));
            }
            case FLOAT -> {
                memPutFloat(ptr, value.x);
                memPutFloat(ptr + 4, value.y);
                memPutFloat(ptr + 8, value.z);
            }
            default -> {
            }
        }
    }

    private static void flushSorted(List<DrawRange> ranges) {
        long currentKey = ranges.get(0).stateKey();
        int groupStart = 0;

        for (int i = 0; i <= ranges.size(); i++) {
            boolean endOfList = i == ranges.size();
            boolean keyChanged = !endOfList && ranges.get(i).stateKey() != currentKey;

            if (endOfList || keyChanged) {
                applyStateKey(currentKey);
                flushGroup(ranges, groupStart, i);

                if (!endOfList) {
                    currentKey = ranges.get(i).stateKey();
                    groupStart = i;
                }
            }
        }
    }

    private static void flushGroup(List<DrawRange> ranges, int from, int to) {
        int i = from;

        while (i < to) {
            DrawRange first = ranges.get(i);
            int drawMode = first.drawMode();
            VertexFormat format = first.format();
            int totalBytes = 0;
            int totalVertices = 0;
            int subEnd = i;

            while (subEnd < to) {
                DrawRange range = ranges.get(subEnd);
                if (range.drawMode() != drawMode || !range.format().equals(format)) {
                    break;
                }

                totalBytes += range.byteLength();
                totalVertices += range.vertexCount();
                subEnd++;

                if (!canMergeDrawMode(drawMode)) {
                    break;
                }
            }

            drawPackedBatch(ranges, i, subEnd, totalBytes, totalVertices, drawMode, format);
            i = subEnd;
        }
    }

    private static boolean canMergeDrawMode(int drawMode) {
        return drawMode != GL11.GL_TRIANGLE_STRIP
                && drawMode != GL11.GL_TRIANGLE_FAN
                && drawMode != GL11.GL_LINE_STRIP
                && drawMode != GL11.GL_LINE_LOOP
                && drawMode != GL11.GL_QUAD_STRIP
                && drawMode != GL11.GL_POLYGON;
    }

    private static void drawPackedBatch(
            List<DrawRange> ranges,
            int from,
            int to,
            int totalBytes,
            int totalVertices,
            int drawMode,
            VertexFormat format
    ) {
        if (totalVertices <= 0) {
            return;
        }

        ensureMergeCapacity(totalBytes);
        long writePtr = mergeAddress;

        for (int i = from; i < to; i++) {
            DrawRange range = ranges.get(i);
            memCopy(batchAddress + range.byteOffset(), writePtr, range.byteLength());
            writePtr += range.byteLength();
        }

        ByteBuffer packed = mergeBuffer.duplicate();
        packed.position(0);
        packed.limit(totalBytes);
        VanillaBufferBuilderRenderer.drawRaw(packed, format, totalVertices, drawMode, "DeferredParticle");
    }

    private static void ensureBatchCapacity(int requiredBytes) {
        if (requiredBytes <= batchCapacity) {
            return;
        }

        int newCapacity = growCapacity(batchCapacity, requiredBytes);
        ByteBuffer old = batchBuffer;
        ByteBuffer replacement = memAlloc(newCapacity);
        memCopy(memAddress0(old), memAddress0(replacement), batchPosition);
        memFree(old);

        batchBuffer = replacement;
        batchAddress = memAddress0(replacement);
        batchCapacity = newCapacity;
    }

    private static void ensureMergeCapacity(int requiredBytes) {
        if (requiredBytes <= mergeCapacity) {
            return;
        }

        int newCapacity = growCapacity(mergeCapacity, requiredBytes);
        memFree(mergeBuffer);
        mergeBuffer = memAlloc(newCapacity);
        mergeAddress = memAddress0(mergeBuffer);
        mergeCapacity = newCapacity;
    }

    private static int growCapacity(int current, int required) {
        int capacity = current;
        while (capacity < required) {
            capacity *= 2;
        }
        return capacity;
    }

    static long captureStateKey() {
        int textureId = GLStateManager.getTextures().getTextureUnitBindings(0).getBinding();
        int srcRgb = GLStateManager.getBlendState().getSrcRgb();
        int dstRgb = GLStateManager.getBlendState().getDstRgb();
        boolean blendEnabled = GLStateManager.getBlendMode().isEnabled();
        boolean depthMask = GLStateManager.getDepthState().isMaskEnabled();
        boolean depthTestEnabled = GLStateManager.getDepthTest().isEnabled();
        boolean tex0Enabled = GLStateManager.getTextures().getTextureUnitStates(0).isEnabled();
        boolean tex1Enabled = GLStateManager.getTextures().getTextureUnitStates(1).isEnabled();

        return packStateKey(textureId, srcRgb, dstRgb, blendEnabled, depthMask, tex0Enabled, tex1Enabled, depthTestEnabled);
    }

    static long packStateKey(
            int textureId,
            int srcRgb,
            int dstRgb,
            boolean blendEnabled,
            boolean depthMask,
            boolean tex0Enabled,
            boolean tex1Enabled,
            boolean depthTestEnabled
    ) {
        return ((long) (textureId & 0xFFFFF) << 26)
                | ((long) (srcRgb & 0xFFF) << 14)
                | ((long) (dstRgb & 0xFFF) << 2)
                | (blendEnabled ? 2L : 0L)
                | (depthMask ? 1L : 0L)
                | (tex0Enabled ? (1L << 46) : 0L)
                | (tex1Enabled ? (1L << 47) : 0L)
                | (depthTestEnabled ? (1L << 48) : 0L);
    }

    static int unpackTextureId(long key) {
        return (int) ((key >> 26) & 0xFFFFF);
    }

    static int unpackSrcRgb(long key) {
        return (int) ((key >> 14) & 0xFFF);
    }

    static int unpackDstRgb(long key) {
        return (int) ((key >> 2) & 0xFFF);
    }

    static boolean unpackBlendEnabled(long key) {
        return (key & 2L) != 0;
    }

    static boolean unpackDepthMask(long key) {
        return (key & 1L) != 0;
    }

    static boolean unpackTex0Enabled(long key) {
        return (key & (1L << 46)) != 0;
    }

    static boolean unpackTex1Enabled(long key) {
        return (key & (1L << 47)) != 0;
    }

    static boolean unpackDepthTestEnabled(long key) {
        return (key & (1L << 48)) != 0;
    }

    static void applyStateKey(long key) {
        int textureId = unpackTextureId(key);
        int srcRgb = unpackSrcRgb(key);
        int dstRgb = unpackDstRgb(key);
        boolean blendEnabled = unpackBlendEnabled(key);
        boolean depthMask = unpackDepthMask(key);
        boolean tex0Enabled = unpackTex0Enabled(key);
        boolean tex1Enabled = unpackTex1Enabled(key);
        boolean depthTestEnabled = unpackDepthTestEnabled(key);

        // Route through GLStateManager so texture-unit enable changes post TEXTURE_UNIT_STATE;
        // bypassing the event silently desyncs Iris pipeline input tracking (issue #41).
        GLStateManager.setTexture2DEnabled(0, tex0Enabled);
        GLStateManager.setTexture2DEnabled(1, tex1Enabled);
        GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GLStateManager.glBlendFunc(srcRgb, dstRgb);
        if (blendEnabled) {
            GLStateManager.enableBlend();
        } else {
            GLStateManager.disableBlend();
        }
        GLStateManager.glDepthMask(depthMask);
        // FindMe and other custom particles temporarily disable testing to render through blocks.
        applyDepthTest(depthTestEnabled);
    }

    private static void applyDepthTest(boolean enabled) {
        if (enabled) {
            GLStateManager.enableDepthTest();
        } else {
            GLStateManager.disableDepthTest();
        }
    }

    private record DrawRange(long stateKey, int byteOffset, int byteLength, VertexFormat format, int vertexCount, int drawMode) {
    }
}

