package com.gtnewhorizons.angelica.glsm.streaming;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.DefaultVertexFormat;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormat;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import org.lwjgl.opengl.GL11;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memCopy;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memGetFloat;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memPutFloat;

/**
 * Rewrites line primitives as quads for shaders that widen lines in the vertex stage.
 *
 * <p>Each segment becomes six vertices carrying the segment's other endpoint in the normal attribute slot; the
 * vertex shader turns them into a screen-space aligned quad. Line width is only known at draw time, so the
 * expansion carries geometry and the shader applies the width.</p>
 */
public final class LineQuads {

    public static final int VERTICES_PER_SEGMENT = 6;

    private static final VertexFormatElement NORMAL_SLOT = new VertexFormatElement(0, VertexFormatElement.Type.FLOAT,
        VertexFormatElement.Usage.NORMAL, 3, VertexFlags.NORMAL_BIT, DefaultVertexFormat.NORMAL_ELEMENT.getWriter());

    private static final VertexFormat[] QUAD_FORMATS = new VertexFormat[VertexFlags.BITSET_SIZE];
    private static final VertexFormatElement[][] QUAD_ELEMENTS = new VertexFormatElement[VertexFlags.BITSET_SIZE][];
    private static final int[][] SOURCE_OFFSETS = new int[VertexFlags.BITSET_SIZE][];

    static {
        for (int flags = 0; flags < QUAD_FORMATS.length; flags++) {
            if ((flags & VertexFlags.NORMAL_BIT) == 0) continue;
            final VertexFormatElement[] elements = DefaultVertexFormat.ALL_FORMATS[flags].getElements()
                .toArray(new VertexFormatElement[0]);
            for (int i = 0; i < elements.length; i++) {
                if (elements[i] == DefaultVertexFormat.NORMAL_ELEMENT) elements[i] = NORMAL_SLOT;
            }
            // The local VertexFormat constructor publishes itself into ALL_FORMATS; the quad layout must not
            // replace the canonical packed-normal format every other draw resolves through that table.
            final VertexFormat canonical = DefaultVertexFormat.ALL_FORMATS[flags];
            QUAD_FORMATS[flags] = new VertexFormat(elements);
            DefaultVertexFormat.ALL_FORMATS[flags] = canonical;
            QUAD_ELEMENTS[flags] = elements;
        }
        for (int flags = 0; flags < SOURCE_OFFSETS.length; flags++) {
            final VertexFormatElement[] quadElements = QUAD_ELEMENTS[flags | VertexFlags.NORMAL_BIT];
            final int[] offsets = new int[quadElements.length];
            for (int i = 0; i < quadElements.length; i++) {
                offsets[i] = offsetOf(DefaultVertexFormat.ALL_FORMATS[flags], quadElements[i]);
            }
            SOURCE_OFFSETS[flags] = offsets;
        }
    }

    private LineQuads() {}

    public static boolean isLineMode(int drawMode) {
        return drawMode == GL11.GL_LINES || drawMode == GL11.GL_LINE_STRIP || drawMode == GL11.GL_LINE_LOOP;
    }

    public static int segmentCount(int drawMode, int vertexCount) {
        return switch (drawMode) {
            case GL11.GL_LINES -> vertexCount / 2;
            case GL11.GL_LINE_STRIP -> Math.max(0, vertexCount - 1);
            case GL11.GL_LINE_LOOP -> vertexCount < 2 ? 0 : vertexCount;
            default -> 0;
        };
    }

    public static boolean disableCulling() {
        final boolean wasEnabled = GLSMRasterizer.getCullState().isEnabled();
        if (wasEnabled) {
            GLSMRasterizer.disableCull();
        }
        return wasEnabled;
    }

    public static void restoreCulling(boolean wasEnabled) {
        if (wasEnabled) {
            GLSMRasterizer.enableCull();
        }
    }

    /**
     * Marks the current draw as line quads and switches culling off for it: the quad winding follows the line
     * direction, so a culled quad drops every line that runs the other way.
     *
     * @return the cull state to hand back to {@link #endDraw(boolean)}
     */
    public static boolean beginDraw(int vertexFlags) {
        GLStateManager.getVaoManager().setVertexFlags(vertexFlags);
        final boolean culled = disableCulling();
        GLStateManager.ctx().lineQuadsActive = true;
        return culled;
    }

    public static void endDraw(boolean culled) {
        GLStateManager.ctx().lineQuadsActive = false;
        restoreCulling(culled);
    }

    public static VertexFormat quadFormat(VertexFormat lineFormat) {
        return QUAD_FORMATS[lineFormat.getVertexFlags() | VertexFlags.NORMAL_BIT];
    }

    public static boolean isQuadFormat(VertexFormat format) {
        return QUAD_FORMATS[format.getVertexFlags()] == format;
    }

    public static void expandWithDirection(long src, VertexFormat srcFormat, int drawMode, int vertexCount, long dst) {
        expand(src, srcFormat, drawMode, vertexCount, dst, false);
    }

    public static void expandWithOtherEnd(long src, VertexFormat srcFormat, int drawMode, int vertexCount, long dst) {
        expand(src, srcFormat, drawMode, vertexCount, dst, true);
    }

    private static void expand(long src, VertexFormat srcFormat, int drawMode, int vertexCount, long dst, boolean otherEnd) {
        final VertexFormatElement[] dstElements = QUAD_ELEMENTS[srcFormat.getVertexFlags() | VertexFlags.NORMAL_BIT];
        final int[] srcOffsets = SOURCE_OFFSETS[srcFormat.getVertexFlags()];
        final int srcStride = srcFormat.getVertexSize();
        final int segments = segmentCount(drawMode, vertexCount);
        long out = dst;
        for (int s = 0; s < segments; s++) {
            final int a = drawMode == GL11.GL_LINES ? s * 2 : s;
            final int b = drawMode == GL11.GL_LINE_LOOP && s == vertexCount - 1 ? 0 : a + 1;
            final long srcA = src + (long) a * srcStride;
            final long srcB = src + (long) b * srcStride;
            // What A's and B's corners carry in the normal slot
            final float ax, ay, az, bx, by, bz;
            if (otherEnd) {
                ax = memGetFloat(srcB); ay = memGetFloat(srcB + 4); az = memGetFloat(srcB + 8);
                bx = memGetFloat(srcA); by = memGetFloat(srcA + 4); bz = memGetFloat(srcA + 8);
            } else {
                final float x = memGetFloat(srcB) - memGetFloat(srcA);
                final float y = memGetFloat(srcB + 4) - memGetFloat(srcA + 4);
                final float z = memGetFloat(srcB + 8) - memGetFloat(srcA + 8);
                final float lengthSquared = x * x + y * y + z * z;
                final float scale = lengthSquared == 0.0f ? 0.0f : 1.0f / (float) Math.sqrt(lengthSquared);
                ax = bx = x * scale;
                ay = by = y * scale;
                az = bz = z * scale;
            }

            // Corner order matches the triangle strip the vertex shader widens: A A B A B B
            out = writeVertex(srcA, out, dstElements, srcOffsets, ax, ay, az);
            out = writeVertex(srcA, out, dstElements, srcOffsets, ax, ay, az);
            out = writeVertex(srcB, out, dstElements, srcOffsets, bx, by, bz);
            out = writeVertex(srcA, out, dstElements, srcOffsets, ax, ay, az);
            out = writeVertex(srcB, out, dstElements, srcOffsets, bx, by, bz);
            out = writeVertex(srcB, out, dstElements, srcOffsets, bx, by, bz);
        }
    }

    private static long writeVertex(long src, long dst, VertexFormatElement[] dstElements, int[] srcOffsets, float x, float y, float z) {
        long out = dst;
        for (int i = 0; i < dstElements.length; i++) {
            final VertexFormatElement element = dstElements[i];
            final int size = element.getByteSize();
            if (element == NORMAL_SLOT) {
                memPutFloat(out, x);
                memPutFloat(out + 4, y);
                memPutFloat(out + 8, z);
            } else {
                memCopy(src + srcOffsets[i], out, size);
            }
            out += size;
        }
        return out;
    }

    private static int offsetOf(VertexFormat format, VertexFormatElement element) {
        int offset = 0;
        for (VertexFormatElement candidate : format.getElements()) {
            if (candidate == element) {
                return offset;
            }
            offset += candidate.getByteSize();
        }
        return -1;
    }
}
