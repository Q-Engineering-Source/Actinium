package com.gtnewhorizons.angelica.glsm.recording;

import com.gtnewhorizon.gtnhlib.client.renderer.vao.IVertexArrayObject;
import com.gtnewhorizon.gtnhlib.client.renderer.vao.IndexBuffer;
import com.gtnewhorizon.gtnhlib.client.renderer.vao.IndexedVAO;
import com.gtnewhorizon.gtnhlib.client.renderer.vao.VAOManager;
import com.gtnewhorizon.gtnhlib.client.renderer.vbo.IVertexBuffer;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.DefaultVertexFormat;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormat;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.streaming.LineQuads;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAddress0;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAlloc;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memCopy;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memFree;
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
        final DisplayListVBO.SubVBO[] vbos = new DisplayListVBO.SubVBO[count];
        final ArrayList<ByteBuffer> allBuffers = new ArrayList<>();
        for (int i = 0; i < VertexFlags.BITSET_SIZE; i++) {
            List<FormatData> formatData = formats[i];
            if (formatData == null) continue;
            final VertexFormat format = DefaultVertexFormat.ALL_FORMATS[i];
            int start = 0;
            final IVertexArrayObject vao = VAOManager.createStorageVAO(format, -1, 0); // drawMode will be ignored
            final IVertexBuffer vbo = vao.getVBO();
            // drawIndex, start, vertexCount, drawMode for each line draw
            final IntArrayList lineDraws = GLStateManager.wideLineEmulationEnabled ? new IntArrayList() : null;
            for (FormatData data : formatData) {
                int vertexCount;
                final List<ByteBuffer> drawBuffers = data.buffers;
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
                if (data.drawMode == GL11.GL_QUADS) {
                    final IVertexArrayObject indexedVAO = new IndexedVAO(vbo, IndexBuffer.convertQuadsToTrigs(start, start + vertexCount));
                    vbos[data.drawIndex] = new DisplayListVBO.SubVBO(indexedVAO, GL11.GL_TRIANGLES, 0, vertexCount / 4 * 6);
                } else if (data.drawMode == GL11.GL_QUAD_STRIP) {
                    // GL_QUAD_STRIP is removed in core profile; an even-length GL_TRIANGLE_STRIP produces the same quads
                    vbos[data.drawIndex] = new DisplayListVBO.SubVBO(vao, GL11.GL_TRIANGLE_STRIP, start, vertexCount & ~1);
                } else if (data.drawMode == GL11.GL_POLYGON) {
                    // GL_POLYGON is removed in core profile; convert to GL_TRIANGLE_FAN which is equivalent for convex polygons
                    vbos[data.drawIndex] = new DisplayListVBO.SubVBO(vao, GL11.GL_TRIANGLE_FAN, start, vertexCount);
                } else {
                    vbos[data.drawIndex] = new DisplayListVBO.SubVBO(vao, data.drawMode, start, vertexCount);
                    if (lineDraws != null && LineQuads.segmentCount(data.drawMode, vertexCount) > 0) {
                        lineDraws.add(data.drawIndex);
                        lineDraws.add(start);
                        lineDraws.add(vertexCount);
                        lineDraws.add(data.drawMode);
                    }
                }


                start += vertexCount;
            }
            MergedBuffer bigBuffer = mergeAndDelete(allBuffers);
            vbo.allocate(bigBuffer.buffer, start);
            if (lineDraws != null && !lineDraws.isEmpty()) {
                attachLineQuads(vbos, format, bigBuffer.buffer, lineDraws);
            }
            nmemFree(bigBuffer.address);

            allBuffers.clear();
        }
        return new DisplayListVBO(vbos);
    }

    // The line width is only known at playback, so lines keep their own draw and also get quads to widen
    private static void attachLineQuads(DisplayListVBO.SubVBO[] vbos, VertexFormat format, ByteBuffer vertices, IntArrayList lineDraws) {
        final VertexFormat quadFormat = LineQuads.quadFormat(format);
        final int quadSize = quadFormat.getVertexSize();
        int quadVertices = 0;
        for (int i = 0; i < lineDraws.size(); i += 4) {
            quadVertices += LineQuads.segmentCount(lineDraws.getInt(i + 3), lineDraws.getInt(i + 2)) * LineQuads.VERTICES_PER_SEGMENT;
        }

        final ByteBuffer quads = memAlloc(quadVertices * quadSize);
        final long src = memAddress0(vertices);
        final long dst = memAddress0(quads);
        final int stride = format.getVertexSize();
        final IVertexArrayObject quadVao = VAOManager.createStorageVAO(quadFormat, -1, 0);
        int quadStart = 0;
        for (int i = 0; i < lineDraws.size(); i += 4) {
            final int start = lineDraws.getInt(i + 1);
            final int count = lineDraws.getInt(i + 2);
            final int mode = lineDraws.getInt(i + 3);
            final int corners = LineQuads.segmentCount(mode, count) * LineQuads.VERTICES_PER_SEGMENT;
            LineQuads.expandWithOtherEnd(src + (long) start * stride, format, mode, count, dst + (long) quadStart * quadSize);
            vbos[lineDraws.getInt(i)].setLineQuads(quadVao, quadStart, corners, quadFormat.getVertexFlags());
            quadStart += corners;
        }
        quadVao.getVBO().allocate(quads, quadVertices);
        memFree(quads);
    }

    private static MergedBuffer mergeAndDelete(List<ByteBuffer> buffers) {
        if (buffers.size() == 1) {
            final ByteBuffer buffer = buffers.get(0);
            return new MergedBuffer(buffer, memAddress0(buffer));
        }
        int needed = 0;
        for (ByteBuffer buffer : buffers) {
            needed += buffer.remaining();
        }

        ByteBuffer out = memAlloc(needed);
        final long outAddress = memAddress0(out);
        long dst = outAddress;

        for (ByteBuffer buffer : buffers) {
            final int len = buffer.remaining();
            final long address = memAddress0(buffer);
            memCopy(address, dst, len);
            dst += len;

            nmemFree(address);
        }

        out.limit(needed);
        return new MergedBuffer(out, outAddress);
    }

    private static final class MergedBuffer {
        private final ByteBuffer buffer;
        private final long address;

        private MergedBuffer(ByteBuffer buffer, long address) {
            this.buffer = buffer;
            this.address = address;
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
