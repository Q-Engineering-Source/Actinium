package com.gtnewhorizons.angelica.glsm.states;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement.Usage;
import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * Per-VAO vertex attribute pointer state tracking (Mesa-style).
 */
public class VertexAttribState {
    public static final int MAX_ATTRIBS = 16;

    private static final Int2ObjectOpenHashMap<Attrib[]> vaoAttribs = new Int2ObjectOpenHashMap<>();
    private static volatile Attrib[] current;
    private static int currentVAO;
    private static final ArrayDeque<Attrib[]> pool = new ArrayDeque<>();
    private static int clientSideEnabledCount;

    public static synchronized void init(int defaultVAO) {
        vaoAttribs.clear();
        pool.clear();
        current = allocAttribArray();
        currentVAO = defaultVAO;
        vaoAttribs.put(defaultVAO, current);
        clientSideEnabledCount = 0;
    }

    public static synchronized void onBindVertexArray(int newVAO) {
        final Attrib[] attribs = vaoAttribs.computeIfAbsent(newVAO, k -> allocAttribArray());
        currentVAO = newVAO;
        current = attribs;
        recomputeClientSideCount(attribs);
    }

    public static synchronized void onDeleteVertexArray(int vaoId) {
        final Attrib[] arr = vaoAttribs.remove(vaoId);
        if (arr != null) {
            pool.addLast(arr);
        }
    }

    public static synchronized void reset() {
        reset(currentVAO);
    }

    /**
     * Clears tracked VAO attributes while keeping the currently bound VAO ready to receive new state.
     * This prevents GL calls issued after a reset from observing an uninitialized current array.
     */
    public static synchronized void reset(int boundVAO) {
        vaoAttribs.clear();
        pool.clear();
        current = allocAttribArray();
        currentVAO = boundVAO;
        vaoAttribs.put(boundVAO, current);
        clientSideEnabledCount = 0;
    }

    private static Attrib[] allocAttribArray() {
        Attrib[] arr = pool.pollFirst();
        if (arr != null) {
            for (Attrib a : arr) a.reset();
            return arr;
        }
        arr = new Attrib[MAX_ATTRIBS];
        for (int i = 0; i < MAX_ATTRIBS; i++) arr[i] = new Attrib();
        return arr;
    }

    public static void set(int index, int size, int type, boolean normalized, int stride, long offset, int vboId) {
        if (index < 0 || index >= MAX_ATTRIBS) return;
        final Attrib a = current[index];
        final boolean was = a.isClientSide();
        a.size = size;
        a.type = type;
        a.normalized = normalized;
        a.stride = stride;
        a.offset = offset;
        a.vboId = vboId;
        a.clientPointer = null;
        if (was) clientSideEnabledCount--;
    }

    public static void set(int index, int size, int type, boolean normalized, int stride, ByteBuffer pointer, int vboId) {
        if (index < 0 || index >= MAX_ATTRIBS) return;
        final Attrib a = current[index];
        final boolean was = a.isClientSide();
        a.size = size;
        a.type = type;
        a.normalized = normalized;
        a.stride = stride;
        a.offset = 0;
        a.vboId = vboId;
        a.clientPointer = (vboId == 0 && pointer != null) ? captureClientPointer(pointer) : null;
        final boolean now = a.isClientSide();
        if (was != now) clientSideEnabledCount += now ? 1 : -1;
    }

    public static void setEnabled(int index, boolean enabled) {
        if (index < 0 || index >= MAX_ATTRIBS) return;
        final Attrib a = current[index];
        if (a.clientPointer != null && a.enabled != enabled) {
            clientSideEnabledCount += enabled ? 1 : -1;
        }
        a.enabled = enabled;
    }

    public static Attrib get(int index) {
        return current[index];
    }

    /**
     * Reports whether the attribute at {@code location} is currently enabled on the bound VAO
     * without requiring {@link #init} to have run (returns {@code false} when no VAO state exists
     * yet). Used to detect whether a draw supplies per-vertex data for a slot — e.g. the extended
     * multi-texture UV slots 2/3 (issue #175) — so the FFP vertex shader can source its varying
     * from the attribute instead of a per-draw constant.
     */
    public static boolean isAttribEnabled(int location) {
        if (current == null || location < 0 || location >= MAX_ATTRIBS) {
            return false;
        }
        return current[location].enabled;
    }

    /**
     * Computes how many leading bytes of {@code a.clientPointer} a draw over vertices
     * [first, first + count) can actually read. The captured pointer deliberately spans the
     * whole underlying allocation (mods like HBM-CE mutate the Java limit after setting the
     * pointer), so the Java limit is never consulted; only the draw's first/count may narrow
     * the range. The last read byte of the final vertex is (first + count - 1) * stride +
     * vertexSize - 1, which stays correct even for stride smaller than the vertex size.
     * Negative strides read backwards from the pointer, so they fall back to the full
     * captured range. The result is always clamped to the captured allocation.
     */
    public static int computeUploadLength(Attrib a, int first, int count) {
        final int remaining = a.clientPointer.remaining();
        if (count <= 0) return 0;
        if (a.stride < 0) return remaining;
        final int stride = a.stride > 0 ? a.stride : a.size * a.typeSizeBytes();
        final long lastByteExclusive = (long) (first + count - 1) * stride + (long) a.size * a.typeSizeBytes();
        return (int) Math.min(remaining, lastByteExclusive);
    }

    /**
     * Captures the native pointer address without treating the Java buffer limit as its GL
     * allocation boundary. HBM reuses a BufferBuilder allocation and changes its limit between
     * uploads; OpenGL client pointers remain valid for the allocation range.
     */
    private static ByteBuffer captureClientPointer(ByteBuffer pointer) {
        final int position = pointer.position();
        final int capacity = pointer.capacity() - position;
        return MemoryUtilities.memByteBuffer(MemoryUtilities.memAddress(pointer), capacity);
    }



    private static void recomputeClientSideCount(Attrib[] attribs) {
        int count = 0;
        for (int i = 0; i < MAX_ATTRIBS; i++) {
            if (attribs[i].isClientSide()) count++;
        }
        clientSideEnabledCount = count;
    }

    public static boolean hasVBOBoundAttrib() {
        for (int i = 0; i < MAX_ATTRIBS; i++) {
            if (current[i].enabled && current[i].vboId != 0) return true;
        }
        return false;
    }

    /**
     * Returns true if any currently enabled vertex attribute was registered without a VBO
     * (i.e. as a client-side pointer). In core profile, such attribs are treated as null
     * offsets into a non-existent buffer, causing a native crash at draw time.
     */
    public static boolean hasAnyClientSideEnabledAttrib() {
        return clientSideEnabledCount > 0;
    }

    /**
     * Computes the FFP vertex-format flags from the attributes actually enabled on the
     * currently bound VAO. This is the authoritative source both for draws fed by
     * client-side arrays and for the raw GL draw entry points (glDrawElements/glDrawArrays
     * and friends): the format-based flag cache in ShaderManager only reflects the last
     * buffer-state setup (e.g. a VBO format), and client-state calls during third-party
     * model uploads (e.g. HBM-CE's glEnableClientState) can leak COLOR_BIT and friends into
     * the global flags without a matching VAO attribute. Either way the FFP shader would
     * declare attributes the draw does not provide and read the default (0,0,0,1) — a
     * POSITION-only draw such as the legacy end portal TESR renders black. Deriving from
     * the per-VAO attribute enablement is always consistent with the real GL state.
     */
    public static int currentClientArrayVertexFlags() {
        if (current == null) {
            return 0;
        }
        int flags = 0;
        if (get(Usage.COLOR.getAttributeLocation()).enabled) flags |= VertexFlags.COLOR_BIT;
        if (get(Usage.NORMAL.getAttributeLocation()).enabled) flags |= VertexFlags.NORMAL_BIT;
        if (get(Usage.PRIMARY_UV.getAttributeLocation()).enabled) flags |= VertexFlags.TEXTURE_BIT;
        if (get(Usage.SECONDARY_UV.getAttributeLocation()).enabled) flags |= VertexFlags.BRIGHTNESS_BIT;
        return flags;
    }

    public static class Attrib {
        public boolean enabled;
        public int size;
        public int type;
        public boolean normalized;
        public int stride;
        public long offset;
        public int vboId;
        public ByteBuffer clientPointer;

        public boolean isClientSide() {
            return enabled && clientPointer != null;
        }

        public void reset() {
            enabled = false;
            size = 0;
            type = 0;
            normalized = false;
            stride = 0;
            offset = 0;
            vboId = 0;
            clientPointer = null;
        }

        public int effectiveStride() {
            return (stride != 0) ? stride : size * typeSizeBytes();
        }

        public int typeSizeBytes() {
            return glTypeSizeBytes(type);
        }

        public static int glTypeSizeBytes(int glType) {
            return switch (glType) {
                case GL11.GL_FLOAT, GL11.GL_INT, GL11.GL_UNSIGNED_INT -> 4;
                case GL11.GL_DOUBLE -> 8;
                case GL11.GL_SHORT, GL11.GL_UNSIGNED_SHORT -> 2;
                case GL11.GL_BYTE, GL11.GL_UNSIGNED_BYTE -> 1;
                default -> 4;
            };
        }

        public float readComponent(ByteBuffer buf, int base, int component) {
            return switch (type) {
                case GL11.GL_FLOAT -> buf.getFloat(base + component * 4);
                case GL11.GL_DOUBLE -> (float) buf.getDouble(base + component * 8);
                case GL11.GL_INT -> {
                    final int v = buf.getInt(base + component * 4);
                    yield normalized ? v / (float) Integer.MAX_VALUE : (float) v;
                }
                case GL11.GL_UNSIGNED_INT -> {
                    final int v = buf.getInt(base + component * 4);
                    yield normalized ? (v & 0xFFFFFFFFL) / (float) 0xFFFFFFFFL : (float) (v & 0xFFFFFFFFL);
                }
                case GL11.GL_SHORT -> {
                    final short v = buf.getShort(base + component * 2);
                    yield normalized ? v / (float) Short.MAX_VALUE : (float) v;
                }
                case GL11.GL_UNSIGNED_SHORT -> {
                    final int v = buf.getShort(base + component * 2) & 0xFFFF;
                    yield normalized ? v / (float) 0xFFFF : (float) v;
                }
                case GL11.GL_BYTE -> {
                    final byte v = buf.get(base + component);
                    yield normalized ? v / 127.0f : (float) v;
                }
                case GL11.GL_UNSIGNED_BYTE -> {
                    final int v = buf.get(base + component) & 0xFF;
                    yield normalized ? v / 255.0f : (float) v;
                }
                default -> 0.0f;
            };
        }
    }
}
