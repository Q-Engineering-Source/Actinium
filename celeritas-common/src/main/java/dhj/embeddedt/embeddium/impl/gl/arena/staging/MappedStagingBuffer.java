package dhj.embeddedt.embeddium.impl.gl.arena.staging;

import it.unimi.dsi.fastutil.PriorityQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import dhj.embeddedt.embeddium.impl.gl.buffer.*;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.gl.device.RenderDevice;
import dhj.embeddedt.embeddium.impl.gl.functions.BufferCopyFunctions;
import dhj.embeddedt.embeddium.impl.gl.functions.BufferMapRangeFunctions;
import dhj.embeddedt.embeddium.impl.gl.functions.BufferStorageFunctions;
import dhj.embeddedt.embeddium.impl.gl.sync.GlFence;
import dhj.embeddedt.embeddium.impl.gl.util.EnumBitField;
import dhj.embeddedt.embeddium.impl.common.util.MathUtil;

import java.nio.ByteBuffer;
import java.util.List;

public class MappedStagingBuffer implements StagingBuffer {
    private static final EnumBitField<GlBufferStorageFlags> STORAGE_FLAGS =
            EnumBitField.of(GlBufferStorageFlags.PERSISTENT, GlBufferStorageFlags.CLIENT_STORAGE, GlBufferStorageFlags.MAP_WRITE);

    private static final EnumBitField<GlBufferMapFlags> MAP_FLAGS =
            EnumBitField.of(GlBufferMapFlags.PERSISTENT, GlBufferMapFlags.INVALIDATE_BUFFER, GlBufferMapFlags.WRITE, GlBufferMapFlags.EXPLICIT_FLUSH);

    private final FallbackStagingBuffer fallbackStagingBuffer;

    private final MappedBuffer mappedBuffer;
    private final List<CopyCommand> pendingCopies = new ObjectArrayList<>();
    private final PriorityQueue<FencedMemoryRegion> fencedRegions = new ObjectArrayFIFOQueue<>();
    private final StagingFenceBatch fenceBatch = new StagingFenceBatchImpl();

    private int start = 0;
    private int pos = 0;
    private int pendingBytes;

    private final int capacity;
    private int remaining;

    public MappedStagingBuffer(CommandList commandList) {
        this(commandList, 1024 * 1024 * 16 /* 16 MB */);
    }

    public MappedStagingBuffer(CommandList commandList, int capacity) {
        GlImmutableBuffer buffer = commandList.createImmutableBuffer(capacity, STORAGE_FLAGS);
        GlBufferMapping map = commandList.mapBuffer(buffer, 0, capacity, MAP_FLAGS);

        this.mappedBuffer = new MappedBuffer(buffer, map);
        this.fallbackStagingBuffer = new FallbackStagingBuffer(commandList);
        this.capacity = capacity;
        this.remaining = this.capacity;
    }

    public static boolean isSupported(RenderDevice instance) {
        var functions = instance.getDeviceFunctions();
        return functions.bufferStorageFunctions() != BufferStorageFunctions.NONE
                && functions.bufferCopyFunctions() != BufferCopyFunctions.PIXEL_PACK
                && functions.bufferMapRangeFunctions() == BufferMapRangeFunctions.CORE;
    }

    @Override
    public void enqueueCopy(CommandList commandList, ByteBuffer data, GlBuffer dst, long writeOffset) {
        int length = data.remaining();
        if (length == 0) {
            return;
        }

        if (length > this.remaining) {
            this.fallbackStagingBuffer.enqueueCopy(commandList, data, dst, writeOffset);

            return;
        }

        int remaining = this.capacity - this.pos;

        // Split the transfer in two if we have enough available memory at the end and start of the buffer
        if (length > remaining) {
            int split = length - remaining;

            this.addTransfer(data, 0, remaining, dst, this.pos, writeOffset);
            this.addTransfer(data, remaining, split, dst, 0, writeOffset + remaining);

            this.pos = split;
        } else {
            this.addTransfer(data, 0, length, dst, this.pos, writeOffset);
            this.pos += length;
            if (this.pos == this.capacity) {
                this.pos = 0;
            }
        }

        this.remaining -= length;
        this.pendingBytes += length;
    }

    private void addTransfer(ByteBuffer data, int sourceOffset, int length, GlBuffer dst, long readOffset, long writeOffset) {
        this.mappedBuffer.map.write(data, sourceOffset, (int) readOffset, length);
        if (!this.pendingCopies.isEmpty()) {
            CopyCommand last = this.pendingCopies.get(this.pendingCopies.size() - 1);
            if (last.buffer == dst && last.readOffset + last.bytes == readOffset
                    && last.writeOffset + last.bytes == writeOffset) {
                last.bytes += length;
                return;
            }
        }
        this.pendingCopies.add(new CopyCommand(dst, readOffset, writeOffset, length));
    }

    @Override
    public void beginUploadBatch() {
        this.fenceBatch.begin();
    }

    @Override
    public void endUploadBatch(CommandList commandList) {
        this.fenceBatch.end();
        this.fenceTransferredBytes(commandList);
    }

    @Override
    public void flush(CommandList commandList) {
        this.fallbackStagingBuffer.flush(commandList);
        if (this.pendingCopies.isEmpty()) {
            return;
        }

        int bytes = this.pendingBytes;
        // Count bytes rather than comparing positions: a full wrapped ring can end at its start.
        int tailBytes = Math.min(bytes, this.capacity - this.start);
        if (tailBytes > 0) {
            commandList.flushMappedRange(this.mappedBuffer.map, this.start, tailBytes);
        }
        if (bytes > tailBytes) {
            commandList.flushMappedRange(this.mappedBuffer.map, 0, bytes - tailBytes);
        }

        for (int i = 0; i < this.pendingCopies.size(); i++) {
            CopyCommand command = this.pendingCopies.get(i);
            commandList.copyBufferSubData(this.mappedBuffer.buffer, command.buffer, command.readOffset, command.writeOffset, command.bytes);
        }
        this.pendingCopies.clear();
        this.pendingBytes = 0;
        this.fenceBatch.addTransferredBytes(bytes);
        this.fenceTransferredBytes(commandList);
        this.start = this.pos;
    }

    private void fenceTransferredBytes(CommandList commandList) {
        int bytes = this.fenceBatch.takeFenceBytes();
        if (bytes > 0) {
            // remaining is restored only by flip(), once this fence actually signals.
            this.fencedRegions.enqueue(new FencedMemoryRegion(commandList.createFence(), bytes));
        }
    }

    @Override
    public void delete(CommandList commandList) {
        this.mappedBuffer.delete(commandList);
        this.fallbackStagingBuffer.delete(commandList);
        this.pendingCopies.clear();

        // flip() only reclaims fences that have already signaled, so anything still in flight has to be deleted here
        // or every renderer teardown leaks a GL sync object.
        while (!this.fencedRegions.isEmpty()) {
            this.fencedRegions.dequeue().fence().delete();
        }
    }

    @Override
    public void flip() {
        while (!this.fencedRegions.isEmpty()) {
            var region = this.fencedRegions.first();
            var fence = region.fence();

            if (!fence.isCompleted()) {
                break;
            }

            fence.delete();

            this.fencedRegions.dequeue();
            this.remaining += region.length();
        }
    }

    private static final class CopyCommand {
        private final GlBuffer buffer;
        private final long readOffset;
        private final long writeOffset;

        private long bytes;

        private CopyCommand(GlBuffer buffer, long readOffset, long writeOffset, long bytes) {
            this.buffer = buffer;
            this.readOffset = readOffset;
            this.writeOffset = writeOffset;
            this.bytes = bytes;
        }

    }

    private record MappedBuffer(GlImmutableBuffer buffer,
                                GlBufferMapping map) {
        public void delete(CommandList commandList) {
            commandList.unmap(this.map);
            commandList.deleteBuffer(this.buffer);
        }
    }

    private record FencedMemoryRegion(GlFence fence, int length) {

    }

    @Override
    public String toString() {
        return "Mapped (%s/%s MiB)".formatted(MathUtil.toMib(this.remaining), MathUtil.toMib(this.capacity));
    }
}
