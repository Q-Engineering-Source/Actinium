package com.gtnewhorizons.angelica.glsm.ffp;

import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import org.lwjgl.opengl.GL11;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memPutFloat;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memPutInt;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memPutLong;

/**
 * Shared per-instance attribute layout for transforms, tint, overlay, lightmap and entity IDs.
 *
 * <p>The head is the common 64-byte prefix used by template and cube instances. A template adds a
 * lightmap at byte 64 for a 72-byte stride; cube instances extend the head with cube texture data.</p>
 */
public final class InstancedAttribs {

    public static final int LOC_ROW0 = 5;
    public static final int LOC_ROW1 = 6;
    public static final int LOC_ROW2 = 7;
    public static final int LOC_COLOR = 8;
    public static final int LOC_OVERLAY = 9;
    public static final int LOC_LIGHTMAP = 10;
    public static final int LOC_ENTITY = 11;

    public static final int OFFSET_ROWS = 0;
    public static final int OFFSET_COLOR = 48;
    public static final int OFFSET_OVERLAY = 52;
    public static final int OFFSET_ENTITY = 56;
    public static final int HEAD_SIZE = 64;
    public static final int OFFSET_LIGHTMAP = HEAD_SIZE;
    public static final int STRIDE = 72;

    /** Entity, block entity and item IDs of an instance without an entity owner. */
    public static final long DEFAULT_ENTITY_IDS = packEntityInfo(-1, 0, -1);

    public static void writeHead(long ptr, float[] colMajor, int off, int colorABGR, int overlayABGR,
                                 long entityInfo) {
        writeRows(ptr, colMajor, off);
        writeTail(ptr, colorABGR, overlayABGR, entityInfo);
    }

    private static void writeRows(long ptr, float[] colMajor, int off) {
        for (int row = 0; row < 3; row++) {
            final long rowPtr = ptr + OFFSET_ROWS + row * 16L;
            memPutFloat(rowPtr, colMajor[off + row]);
            memPutFloat(rowPtr + 4, colMajor[off + 4 + row]);
            memPutFloat(rowPtr + 8, colMajor[off + 8 + row]);
            memPutFloat(rowPtr + 12, colMajor[off + 12 + row]);
        }
    }

    public static void writeTail(long ptr, int colorABGR, int overlayABGR, long entityInfo) {
        memPutInt(ptr + OFFSET_COLOR, colorABGR);
        memPutInt(ptr + OFFSET_OVERLAY, overlayABGR);
        memPutLong(ptr + OFFSET_ENTITY, entityInfo);
    }

    public static void writeLightmap(long ptr, int packedLight) {
        memPutFloat(ptr, packedLight & 0xFFFF);
        memPutFloat(ptr + 4, (packedLight >>> 16) & 0xFFFF);
    }

    public static long packEntityInfo(int entity, int blockEntity, int item) {
        return (entity & 0xFFFFL) | ((blockEntity & 0xFFFFL) << 16) | ((item & 0xFFFFL) << 32);
    }

    public static int unpackId(long info, int shift) {
        final int id = (int) (info >>> shift) & 0xFFFF;
        return id == 0xFFFF ? -1 : id;
    }

    public static float normalizedId(long info, int shift) {
        return ((info >>> shift) & 0xFFFF) / 65535.0F;
    }

    public static void pointHead(long base, int stride) {
        for (int row = 0; row < 3; row++) {
            GLSMVertexInput.glVertexAttribPointer(LOC_ROW0 + row, 4, GL11.GL_FLOAT, false, stride,
                base + OFFSET_ROWS + row * 16L);
        }
        GLSMVertexInput.glVertexAttribPointer(LOC_COLOR, 4, GL11.GL_UNSIGNED_BYTE, true, stride,
            base + OFFSET_COLOR);
        GLSMVertexInput.glVertexAttribPointer(LOC_OVERLAY, 4, GL11.GL_UNSIGNED_BYTE, true, stride,
            base + OFFSET_OVERLAY);
        pointEntityIds(LOC_ENTITY, stride, base + OFFSET_ENTITY);
    }

    public static void pointEntityIds(int location, int stride, long offset) {
        GLSMVertexInput.glVertexAttribPointer(location, 4, GL11.GL_UNSIGNED_SHORT, true, stride, offset);
    }

    public static void pointTemplate(long base) {
        pointHead(base, STRIDE);
        GLSMVertexInput.glVertexAttribPointer(LOC_LIGHTMAP, 2, GL11.GL_FLOAT, false, STRIDE,
            base + OFFSET_LIGHTMAP);
    }

    public static void enableHeadArrays() {
        for (int location = LOC_ROW0; location <= LOC_ENTITY; location++) {
            enableInstanced(location);
        }
    }

    static void enableInstanced(int location) {
        GLSMVertexInput.glEnableVertexAttribArray(location);
        GLSMVertexInput.glVertexAttribDivisor(location, 1);
    }

    private InstancedAttribs() {
    }
}
