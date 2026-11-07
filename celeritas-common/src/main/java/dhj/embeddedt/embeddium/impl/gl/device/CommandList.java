package dhj.embeddedt.embeddium.impl.gl.device;

import dhj.embeddedt.embeddium.impl.gl.array.GlVertexArray;
import dhj.embeddedt.embeddium.impl.gl.buffer.*;
import dhj.embeddedt.embeddium.impl.gl.buffer.*;
import dhj.embeddedt.embeddium.impl.gl.sync.GlFence;
import dhj.embeddedt.embeddium.impl.gl.tessellation.GlTessellation;
import dhj.embeddedt.embeddium.impl.gl.util.EnumBitField;

import java.nio.ByteBuffer;

public interface CommandList extends AutoCloseable {
    GlMutableBuffer createMutableBuffer();

    GlImmutableBuffer createImmutableBuffer(long bufferSize, EnumBitField<GlBufferStorageFlags> flags);

    void bindVertexArray(GlVertexArray array);

    void uploadData(GlMutableBuffer glBuffer, ByteBuffer byteBuffer, GlBufferUsage usage);

    /**
     * Uploads from {@code ptr} into {@code glBuffer} while it is bound to {@link GlBufferTarget#ARRAY_BUFFER}.
     */
    void uploadData(GlMutableBuffer glBuffer, long ptr, long bytes, GlBufferUsage usage);

    /**
     * Uploads {@code bytes} from {@code ptr} into {@code glBuffer} while it is bound to {@code target}. Callers that
     * draw through another target, such as indirect multidraw, must name it: the target the data lands in has to be
     * the one the draw call binds.
     */
    void uploadData(GlMutableBuffer glBuffer, long ptr, long bytes, GlBufferUsage usage, GlBufferTarget target);

    void copyBufferSubData(GlBuffer src, GlBuffer dst, long readOffset, long writeOffset, long bytes);

    void bindBuffer(GlBufferTarget target, GlBuffer buffer);

    void unbindVertexArray();

    void allocateStorage(GlMutableBuffer buffer, long bufferSize, GlBufferUsage usage);

    void deleteBuffer(GlBuffer buffer);

    void deleteVertexArray(GlVertexArray vertexArray);

    void flush();

    DrawCommandList beginTessellating(GlTessellation tessellation);

    void deleteTessellation(GlTessellation tessellation);

    @Override
    default void close() {
        this.flush();
    }

    GlBufferMapping mapBuffer(GlBuffer buffer, long offset, long length, EnumBitField<GlBufferMapFlags> flags);

    void unmap(GlBufferMapping map);

    void flushMappedRange(GlBufferMapping map, int offset, int length);

    GlFence createFence();
}
