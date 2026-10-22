package dhj.embeddedt.embeddium.impl.gl.arena.staging;

import dhj.embeddedt.embeddium.impl.gl.buffer.GlBuffer;
import dhj.embeddedt.embeddium.impl.gl.buffer.GlBufferUsage;
import dhj.embeddedt.embeddium.impl.gl.buffer.GlMutableBuffer;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;

import java.nio.ByteBuffer;

public class FallbackStagingBuffer implements StagingBuffer {
    private final GlMutableBuffer fallbackBufferObject;
    private boolean uploaded;
    private long uploadCountTotal;
    private long uploadBytesTotal;

    public FallbackStagingBuffer(CommandList commandList) {
        this.fallbackBufferObject = commandList.createMutableBuffer();
    }

    @Override
    public void enqueueCopy(CommandList commandList, ByteBuffer data, GlBuffer dst, long writeOffset) {
        int uploadBytes = data.remaining();
        commandList.uploadData(this.fallbackBufferObject, data, GlBufferUsage.STREAM_COPY);
        commandList.copyBufferSubData(this.fallbackBufferObject, dst, 0, writeOffset, data.remaining());
        this.uploaded = true;
        this.uploadCountTotal++;
        this.uploadBytesTotal += uploadBytes;
    }

    @Override
    public void flush(CommandList commandList) {
        if (this.uploaded) {
            commandList.allocateStorage(this.fallbackBufferObject, 0L, GlBufferUsage.STREAM_COPY);
            this.uploaded = false;
        }
    }

    @Override
    public void delete(CommandList commandList) {
        commandList.deleteBuffer(this.fallbackBufferObject);
    }

    @Override
    public void flip() {

    }

    public long getLiveBytes() {
        return this.fallbackBufferObject.getSize();
    }

    public long getUploadCountTotal() {
        return this.uploadCountTotal;
    }

    public long getUploadBytesTotal() {
        return this.uploadBytesTotal;
    }

    public boolean isResetPending() {
        return this.uploaded;
    }

    @Override
    public String toString() {
        return "Fallback";
    }
}
