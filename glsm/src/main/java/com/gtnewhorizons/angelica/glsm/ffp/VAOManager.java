package com.gtnewhorizons.angelica.glsm.ffp;

import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement.Usage;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.debug.GLSMDebug;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;

import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

public final class VAOManager {
    public static final int MAX_ATTRIBS = 16;

    private static final boolean DEBUG_DRAW_LOGS = Boolean.getBoolean("actinium.glsm.verboseDrawLogs");
    private static final boolean FULL_CLIENT_ARRAY_UPLOAD = Boolean.getBoolean("actinium.glsmFullClientArrayUpload");

    public final Int2ObjectOpenHashMap<VAOData> vaoMap = new Int2ObjectOpenHashMap<>();
    final Int2ObjectOpenHashMap<VAOData> eboOwners = new Int2ObjectOpenHashMap<>();

    private VAOData current;
    private Attrib[] currentAttribs;
    private int currentVertexFlags;
    private int clientSideEnabledCount;
    public int boundEBO;

    private int clientArraysVBO;
    private int clientArraysVBOCapacity;
    private final int[] clientArraysVBOOffsets = new int[MAX_ATTRIBS];
    private final int[] clientArraysVBOUploadLengths = new int[MAX_ATTRIBS];

    public void init(int defaultVAO) {
        vaoMap.clear();
        eboOwners.clear();
        currentVertexFlags = 0;
        clientSideEnabledCount = 0;
        boundEBO = 0;
        final VAOData data = new VAOData();
        current = data;
        currentAttribs = data.attribs;
        vaoMap.put(defaultVAO, data);
    }

    public void reset(int boundVAO) {
        init(boundVAO);
    }

    public boolean isInitialized() {
        return current != null;
    }

    public void onBindEBO(int ebo) {
        boundEBO = ebo;
    }

    public void onBindVertexArrayPre(int vaoId) {
        if (current != null) {
            current.vertexFlags = currentVertexFlags;
            if (current.ebo != boundEBO) {
                removeEboOwner(current.ebo, current);
                current.ebo = boundEBO;
                addEboOwner(current.ebo, current);
            }
            current.clientSideEnabledCount = clientSideEnabledCount;
        }

        VAOData data = vaoMap.get(vaoId);
        if (data == null) {
            data = new VAOData();
            vaoMap.put(vaoId, data);
        }
        current = data;
        currentAttribs = data.attribs;
        currentVertexFlags = data.formatKnown ? data.vertexFlags : currentClientArrayVertexFlags(data.attribs);
        boundEBO = data.ebo;
        clientSideEnabledCount = data.clientSideEnabledCount;
    }

    public void onDeleteVertexArray(int vaoId) {
        final VAOData data = vaoMap.remove(vaoId);
        if (data == null) return;
        removeEboOwner(data.ebo, data);
        if (data == current) {
            current = null;
            currentAttribs = null;
            currentVertexFlags = 0;
            clientSideEnabledCount = 0;
            boundEBO = 0;
        }
    }

    public void onDeleteBuffer(int buffer) {
        if (buffer == 0) return;
        VAOData owner = eboOwners.remove(buffer);
        while (owner != null) {
            final VAOData next = owner.nextEboOwner;
            owner.nextEboOwner = null;
            owner.ebo = 0;
            owner = next;
        }
        if (boundEBO == buffer) boundEBO = 0;
        if (current != null && current.ebo == buffer) current.ebo = 0;
    }

    private void addEboOwner(int ebo, VAOData data) {
        if (ebo == 0) return;
        data.nextEboOwner = eboOwners.put(ebo, data);
    }

    private void removeEboOwner(int ebo, VAOData data) {
        if (ebo == 0) return;
        VAOData node = eboOwners.get(ebo);
        if (node == data) {
            final VAOData next = data.nextEboOwner;
            if (next == null) eboOwners.remove(ebo);
            else eboOwners.put(ebo, next);
            data.nextEboOwner = null;
            return;
        }
        while (node != null) {
            final VAOData next = node.nextEboOwner;
            if (next == data) {
                node.nextEboOwner = data.nextEboOwner;
                data.nextEboOwner = null;
                return;
            }
            node = next;
        }
    }

    public void enableClientVertexFlag(int flag) {
        currentVertexFlags |= flag;
    }

    public void disableClientVertexFlag(int flag) {
        currentVertexFlags &= ~flag;
    }

    public int getVertexFlags() {
        return currentVertexFlags;
    }

    public void setVertexFlags(int flags) {
        currentVertexFlags = flags;
    }

    public void setFormatVertexFlags(int flags) {
        currentVertexFlags = flags;
        if (current != null) {
            current.vertexFlags = flags;
            current.formatKnown = true;
        }
    }

    public void setAttribute(int index, int size, int type, boolean normalized, int stride, long offset, int vboId) {
        if (index < 0 || index >= MAX_ATTRIBS) return;
        final Attrib a = currentAttribs[index];
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

    public void setAttribute(int index, int size, int type, boolean normalized, int stride, ByteBuffer pointer, int vboId) {
        if (index < 0 || index >= MAX_ATTRIBS) return;
        final Attrib a = currentAttribs[index];
        final boolean was = a.isClientSide();
        a.size = size;
        a.type = type;
        a.normalized = normalized;
        a.stride = stride;
        a.offset = 0;
        a.vboId = vboId;
        a.clientPointer = vboId == 0 && pointer != null ? captureClientPointer(pointer) : null;
        final boolean now = a.isClientSide();
        if (was != now) clientSideEnabledCount += now ? 1 : -1;
    }

    public void setEnabled(int index, boolean enabled) {
        if (index < 0 || index >= MAX_ATTRIBS) return;
        final Attrib a = currentAttribs[index];
        if (a.clientPointer != null && a.enabled != enabled) {
            clientSideEnabledCount += enabled ? 1 : -1;
        }
        a.enabled = enabled;
    }

    public Attrib attrib(int index) {
        return currentAttribs[index];
    }

    public boolean isAttribEnabled(int index) {
        return currentAttribs != null && index >= 0 && index < MAX_ATTRIBS && currentAttribs[index].enabled;
    }

    public boolean hasVBOBoundAttrib() {
        if (currentAttribs == null) return false;
        for (Attrib attrib : currentAttribs) {
            if (attrib.enabled && attrib.vboId != 0) return true;
        }
        return false;
    }

    public boolean hasAnyClientSideEnabledAttrib() {
        return clientSideEnabledCount > 0;
    }

    public int currentClientArrayVertexFlags() {
        return currentAttribs == null ? 0 : currentClientArrayVertexFlags(currentAttribs);
    }

    private static int currentClientArrayVertexFlags(Attrib[] attribs) {
        int flags = 0;
        if (attribs[Usage.COLOR.getAttributeLocation()].enabled) flags |= VertexFlags.COLOR_BIT;
        if (attribs[Usage.NORMAL.getAttributeLocation()].enabled) flags |= VertexFlags.NORMAL_BIT;
        if (attribs[Usage.PRIMARY_UV.getAttributeLocation()].enabled) flags |= VertexFlags.TEXTURE_BIT;
        if (attribs[Usage.SECONDARY_UV.getAttributeLocation()].enabled) flags |= VertexFlags.BRIGHTNESS_BIT;
        return flags;
    }

    public void uploadClientArraysToVBO(int first, int count) {
        final boolean perfDebugEnabled = GLSMPerfDebug.isEnabled();
        final long perfStart = perfDebugEnabled ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.GL_CLIENT_ARRAY_UPLOAD) : 0L;
        final boolean fullUpload = FULL_CLIENT_ARRAY_UPLOAD || count < 0;
        int totalBytes = 0;
        for (int i = 0; i < MAX_ATTRIBS; i++) {
            clientArraysVBOOffsets[i] = -1;
            final Attrib a = currentAttribs[i];
            if (!a.enabled || a.clientPointer == null) continue;
            final int uploadLength = fullUpload ? a.clientPointer.remaining() : computeUploadLength(a, first, count);
            clientArraysVBOUploadLengths[i] = uploadLength;
            clientArraysVBOOffsets[i] = totalBytes;
            totalBytes += uploadLength;
        }
        if (totalBytes == 0) {
            if (perfDebugEnabled) GLSMPerfDebug.end(GLSMPerfDebug.Stage.GL_CLIENT_ARRAY_UPLOAD, perfStart);
            return;
        }

        if (clientArraysVBO == 0) clientArraysVBO = RENDER_BACKEND.genBuffers();
        final int savedVBO = GLStateManager.getBoundVBO();
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, clientArraysVBO);

        if (totalBytes > clientArraysVBOCapacity) {
            int newCapacity = Math.max(4096, clientArraysVBOCapacity);
            while (newCapacity < totalBytes) newCapacity *= 2;
            clientArraysVBOCapacity = newCapacity;
        }
        RENDER_BACKEND.bufferData(GL15.GL_ARRAY_BUFFER, clientArraysVBOCapacity, GL15.GL_STREAM_DRAW);
        if (DEBUG_DRAW_LOGS) {
            GLSMDebug.logClientArrayUpload(totalBytes, clientArraysVBOCapacity, clientArraysVBO, savedVBO, clientArraysVBOOffsets);
        }

        for (int i = 0; i < MAX_ATTRIBS; i++) {
            if (clientArraysVBOOffsets[i] < 0) continue;
            final Attrib a = currentAttribs[i];
            final ByteBuffer slice = a.clientPointer.duplicate();
            slice.limit(slice.position() + clientArraysVBOUploadLengths[i]);
            RENDER_BACKEND.bufferSubData(GL15.GL_ARRAY_BUFFER, clientArraysVBOOffsets[i], slice);
            RENDER_BACKEND.vertexAttribPointer(i, a.size, a.type, a.normalized, a.stride, clientArraysVBOOffsets[i]);
        }

        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedVBO);
        if (perfDebugEnabled) {
            GLSMPerfDebug.end(GLSMPerfDebug.Stage.GL_CLIENT_ARRAY_UPLOAD, perfStart);
            GLSMPerfDebug.countClientArrayUpload(totalBytes);
        }
    }

    public static int computeUploadLength(Attrib a, int first, int count) {
        final int remaining = a.clientPointer.remaining();
        if (count <= 0) return 0;
        if (a.stride < 0) return remaining;
        final int stride = a.stride > 0 ? a.stride : a.size * a.typeSizeBytes();
        final long lastByteExclusive = (long) (first + count - 1) * stride + (long) a.size * a.typeSizeBytes();
        return (int) Math.min(remaining, lastByteExclusive);
    }

    private static ByteBuffer captureClientPointer(ByteBuffer pointer) {
        final int position = pointer.position();
        final int capacity = pointer.capacity() - position;
        return MemoryUtilities.memByteBuffer(MemoryUtilities.memAddress(pointer), capacity);
    }

    public static final class VAOData {
        public final Attrib[] attribs = new Attrib[MAX_ATTRIBS];
        public int vertexFlags;
        public boolean formatKnown;
        public int ebo;
        public int clientSideEnabledCount;
        private VAOData nextEboOwner;

        private VAOData() {
            for (int i = 0; i < MAX_ATTRIBS; i++) attribs[i] = new Attrib();
        }
    }

    public static final class Attrib {
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

        public int effectiveStride() {
            return stride != 0 ? stride : size * typeSizeBytes();
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
                    final int value = buf.getInt(base + component * 4);
                    yield normalized ? value / (float) Integer.MAX_VALUE : (float) value;
                }
                case GL11.GL_UNSIGNED_INT -> {
                    final int value = buf.getInt(base + component * 4);
                    yield normalized ? (value & 0xFFFFFFFFL) / (float) 0xFFFFFFFFL : (float) (value & 0xFFFFFFFFL);
                }
                case GL11.GL_SHORT -> {
                    final short value = buf.getShort(base + component * 2);
                    yield normalized ? value / (float) Short.MAX_VALUE : (float) value;
                }
                case GL11.GL_UNSIGNED_SHORT -> {
                    final int value = buf.getShort(base + component * 2) & 0xFFFF;
                    yield normalized ? value / (float) 0xFFFF : (float) value;
                }
                case GL11.GL_BYTE -> {
                    final byte value = buf.get(base + component);
                    yield normalized ? value / 127.0f : (float) value;
                }
                case GL11.GL_UNSIGNED_BYTE -> {
                    final int value = buf.get(base + component) & 0xFF;
                    yield normalized ? value / 255.0f : (float) value;
                }
                default -> 0.0f;
            };
        }
    }
}
