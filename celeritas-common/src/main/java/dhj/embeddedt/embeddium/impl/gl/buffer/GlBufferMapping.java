package dhj.embeddedt.embeddium.impl.gl.buffer;

import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;

import java.nio.ByteBuffer;
import java.util.Objects;

public class GlBufferMapping {
    private final GlBuffer buffer;
    private final ByteBuffer map;

    protected boolean disposed;

    public GlBufferMapping(GlBuffer buffer, ByteBuffer map) {
        this.buffer = buffer;
        this.map = map;
    }

    public void write(ByteBuffer data, int writeOffset) {
        this.write(data, 0, writeOffset, data.remaining());
    }

    /** Copies a subrange relative to the source position without allocating a slice or moving it. */
    public void write(ByteBuffer data, int sourceOffset, int writeOffset, int length) {
        Objects.checkFromIndexSize(sourceOffset, length, data.remaining());
        Objects.checkFromIndexSize(writeOffset, length, this.map.limit());
        MemoryUtilities.memCopy(MemoryUtilities.memAddress(data) + sourceOffset,
                MemoryUtilities.memAddress(this.map, writeOffset), length);
    }

    public GlBuffer getBufferObject() {
        return this.buffer;
    }

    public void dispose() {
        this.disposed = true;
    }

    public boolean isDisposed() {
        return this.disposed;
    }

    public ByteBuffer getMemoryBuffer() {
        return this.map;
    }
}
