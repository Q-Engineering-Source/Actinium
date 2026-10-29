package com.gtnewhorizons.angelica.glsm.recording;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.DefaultVertexFormat;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormat;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAddress0;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAlloc;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memCopy;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memPutShort;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.nmemFree;

/**
 * Compiles multiple ByteBuffers of the same Vertex format into a VBO. Any buffer added to the builder will be freed afterward.
 */
public final class DisplayListVBOBuilder {
    // Removes the need for a HashMap. Since it's only an array of 16 values, this should be faster
    private final List<FormatData>[] formats = new List[VertexFlags.BITSET_SIZE];
    private int count;

    public DisplayListVBOBuilder addDraw(VertexFormat format, int drawMode, List<ByteBuffer> buffers) {
        List<FormatData> data = formats[format.getVertexFlags()];
        if (data == null) {
            data = new ArrayList<>();
            formats[format.getVertexFlags()] = data;
        }
        data.add(new FormatData(buffers, drawMode, count));
        count++;
        return this;
    }

    public DisplayListVBOBuilder addDraws(List<AccumulatedDraw> draws) {
        for (AccumulatedDraw draw : draws) {
            addDraw(draw.format, draw.drawMode, draw.drawBuffers);
        }
        return this;
    }

    public DisplayListVBO build() {
        final GLContextIdentity identity = GLStateManager.currentContextIdentity();
        if (identity == null) {
            throw new IllegalStateException("Display-list draw ranges require a registered current context");
        }
        final DisplayListVBO.SubVBO[] vbos = new DisplayListVBO.SubVBO[count];
        final ArrayList<ByteBuffer> allBuffers = new ArrayList<>();
        final Set<ByteBuffer> unconsumedBuffers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<FormatData> formatData : formats) {
            if (formatData == null) continue;
            for (FormatData data : formatData) {
                unconsumedBuffers.addAll(data.buffers);
            }
        }
        final List<Integer> ownedBuffers = new ArrayList<>();
        final int previousVbo = GLStateManager.getBoundVBO();
        boolean success = false;
        try {
            for (int i = 0; i < VertexFlags.BITSET_SIZE; i++) {
                final List<FormatData> formatData = formats[i];
                if (formatData == null) continue;
                final VertexFormat format = DefaultVertexFormat.ALL_FORMATS[i];
                int start = 0;
                final int vertexBuffer = GLStateManager.glGenBuffers();
                ownedBuffers.add(vertexBuffer);
                for (FormatData data : formatData) {
                    final List<ByteBuffer> drawBuffers = data.buffers;
                    final int vertexCount;
                    if (drawBuffers.size() == 1) {
                        final ByteBuffer buffer = drawBuffers.get(0);
                        allBuffers.add(buffer);
                        vertexCount = format.getVertexCount(buffer);
                    } else {
                        allBuffers.addAll(drawBuffers);
                        int size = 0;
                        for (ByteBuffer buffer : drawBuffers) {
                            size += buffer.remaining();
                        }
                        vertexCount = format.getVertexCount(size);
                    }

                    int drawMode = data.drawMode;
                    int drawStart = start;
                    int drawCount = vertexCount;
                    int elementBuffer = 0;
                    if (data.drawMode == GL11.GL_QUADS) {
                        drawMode = GL11.GL_TRIANGLES;
                        drawStart = 0;
                        drawCount = vertexCount / 4 * 6;
                        elementBuffer = createQuadIndexBuffer(start, vertexCount);
                        ownedBuffers.add(elementBuffer);
                    } else if (data.drawMode == GL11.GL_QUAD_STRIP) {
                        // GL_QUAD_STRIP is removed in core profile; an even-length triangle strip is equivalent.
                        drawMode = GL11.GL_TRIANGLE_STRIP;
                        drawCount = vertexCount & ~1;
                    } else if (data.drawMode == GL11.GL_POLYGON) {
                        // GL_POLYGON is removed in core profile; a triangle fan preserves convex polygons.
                        drawMode = GL11.GL_TRIANGLE_FAN;
                    }

                    vbos[data.drawIndex] = new DisplayListVBO.SubVBO(
                        vertexBuffer, elementBuffer, format, drawMode, drawStart, drawCount, identity.shareGroupId());
                    start += vertexCount;
                }

                MergedBuffer merged = null;
                try {
                    merged = mergeAndDelete(allBuffers);
                    GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
                    GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, merged.buffer, GL15.GL_STATIC_DRAW);
                } finally {
                    if (merged != null && merged.temporary) {
                        nmemFree(merged.address);
                    }
                    freeBuffers(allBuffers);
                    for (ByteBuffer buffer : allBuffers) {
                        unconsumedBuffers.remove(buffer);
                    }
                    allBuffers.clear();
                }
            }
            for (int i = 0; i < vbos.length; i++) {
                if (vbos[i] == null) {
                    throw new IllegalStateException("Display-list draw range " + i + " was not assigned a buffer");
                }
            }
            success = true;
            return new DisplayListVBO(vbos, ownedBuffers.stream().mapToInt(Integer::intValue).toArray(), identity.shareGroupId());
        } finally {
            if (!unconsumedBuffers.isEmpty()) {
                freeBuffers(unconsumedBuffers);
                unconsumedBuffers.clear();
            }
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousVbo);
            if (!success) {
                for (int buffer : ownedBuffers) {
                    GLStateManager.deleteBufferForShareGroup(identity.shareGroupId(), buffer);
                }
                for (DisplayListVBO.SubVBO vbo : vbos) {
                    if (vbo != null) vbo.delete();
                }
            }
        }
    }

    private static int createQuadIndexBuffer(int start, int vertexCount) {
        final int quadCount = vertexCount / 4;
        final int indexCount = quadCount * 6;
        final ByteBuffer indices = memAlloc(indexCount * Short.BYTES);
        final int indexBuffer = GLStateManager.glGenBuffers();
        boolean uploaded = false;
        try {
            long pointer = memAddress0(indices);
            for (int quad = 0; quad < quadCount; quad++) {
                final int first = start + quad * 4;
                memPutShort(pointer, (short) first);
                memPutShort(pointer + 2, (short) (first + 1));
                memPutShort(pointer + 4, (short) (first + 2));
                memPutShort(pointer + 6, (short) (first + 2));
                memPutShort(pointer + 8, (short) (first + 3));
                memPutShort(pointer + 10, (short) first);
                pointer += 12;
            }
            indices.position(0).limit(indexCount * Short.BYTES);
            // Upload through ARRAY_BUFFER because ELEMENT_ARRAY_BUFFER would mutate the current VAO.
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, indexBuffer);
            GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
            uploaded = true;
            return indexBuffer;
        } finally {
            nmemFree(memAddress0(indices));
            if (!uploaded) {
                GLStateManager.glDeleteBuffers(indexBuffer);
            }
        }
    }

    private static MergedBuffer mergeAndDelete(List<ByteBuffer> buffers) {
        if (buffers.size() == 1) {
            final ByteBuffer buffer = buffers.get(0);
            return new MergedBuffer(buffer, memAddress0(buffer), false);
        }
        int needed = 0;
        for (ByteBuffer buffer : buffers) {
            needed += buffer.remaining();
        }

        ByteBuffer out = memAlloc(needed);
        final long outAddress = memAddress0(out);
        long dst = outAddress;
        try {
            for (ByteBuffer buffer : buffers) {
                final int len = buffer.remaining();
                final long address = memAddress0(buffer);
                memCopy(address, dst, len);
                dst += len;
            }
        } catch (RuntimeException | Error exception) {
            nmemFree(outAddress);
            throw exception;
        }

        out.limit(needed);
        return new MergedBuffer(out, outAddress, true);
    }

    private static void freeBuffers(Iterable<ByteBuffer> buffers) {
        for (ByteBuffer buffer : buffers) {
            nmemFree(memAddress0(buffer));
        }
    }

    private static final class MergedBuffer {
        private final ByteBuffer buffer;
        private final long address;
        private final boolean temporary;

        private MergedBuffer(ByteBuffer buffer, long address, boolean temporary) {
            this.buffer = buffer;
            this.address = address;
            this.temporary = temporary;
        }
    }

    private static final class FormatData {

        private final List<ByteBuffer> buffers;
        private final int drawMode;
        private final int drawIndex;

        public FormatData(List<ByteBuffer> buffers, int drawMode, int drawIndex) {
            this.buffers = buffers;
            this.drawMode = drawMode;
            this.drawIndex = drawIndex;
        }
    }
}
